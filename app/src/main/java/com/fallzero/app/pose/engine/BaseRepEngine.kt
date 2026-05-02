package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.data.algorithm.PRBManager
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.MetricSmoother
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 반복 횟수 기반 운동의 공용 상태 머신.
 * 모든 rep-counting 엔진(운동 #1~#7)이 이 클래스를 상속.
 *
 * 상태 흐름 (Q3 — 카운트 시점 앞당김 적용):
 *   IDLE
 *     ↓ metric ≥ partial threshold (예: motion threshold * 0.30)
 *   ATTEMPTING (시도 중, 카운트 안 됨)
 *     ├─ metric ≥ motion threshold → IN_MOTION + count++ (즉시 카운트)
 *     └─ metric < partial threshold → 코칭 큐 발화 + IDLE (시도 실패 = 부족하게 들었다 내림)
 *   IN_MOTION
 *     ↓ metric ≤ return threshold
 *   RETURNING
 *     ↓ metric ≤ return threshold (확정)
 *   IDLE (다음 사이클 준비, 카운트 추가 없음)
 *
 * 캘리브레이션 모드는 별도 흐름:
 *   IDLE → ATTEMPTING → IN_MOTION → RETURNING → IDLE (calibrationReps++)
 *   캘리브레이션은 사이클 전체가 완료되어야 1회 — 정확한 측정 필요.
 *
 * 핵심 방어 메커니즘:
 * 1. MetricSmoother — EMA로 프레임 간 떨림 억제
 * 2. ATTEMPTING 상태 — 부족한 시도와 정상 시도를 구분, 코칭 큐 발화 시점 명확화
 * 3. RETURNING 상태 — 동작 복귀를 별도 단계로 검증
 * 4. 쿨다운 — 카운트 후 MIN_COOLDOWN_MS 동안 재진입 차단
 * 5. 히스테리시스 — 진입(80%) / 복귀(30%) 기준 분리
 * 6. 캘리브레이션 카운트 분리 — 캘리브레이션 2회는 currentCount에 미포함
 */
abstract class BaseRepEngine(
    override val targetCount: Int
) : ExerciseEngine {

    override var currentCount = 0; protected set
    override var isInCalibration = false; protected set
    override var measuredCalibrationPRB = 0f; protected set

    protected var prb = 0f
    protected var state = EngineState.IDLE
    protected var hasErrorThisRep = false

    // 캘리브레이션
    private var calibrationReps = 0
    // 첫 capture를 위한 sentinel (NaN). selectPeak이 NaN이면 candidate를 그대로 채택.
    // metricIncreasing=false 엔진(ChairStand)에서 minOf(0, X)=0 stuck 버그 방지.
    private var calibrationPeak = Float.NaN
    // ATTEMPTING 동안 도달한 최고 metric (실패 시 coachingCue 발화 여부 결정용)
    private var attemptingPeakMetric = Float.NaN

    // 스무딩 (서브클래스에서 alpha 조정 가능)
    protected var smoother = MetricSmoother(alpha = 0.3f)

    // 쿨다운: 카운트 후 300ms 동안 재진입 차단
    private var lastCountTimeMs = 0L
    private val MIN_COOLDOWN_MS = 300L

    // ─── 서브클래스가 구현해야 하는 메서드 ───

    /** 현재 프레임에서 운동 메트릭 추출 (각도, D%, rise 등). raw 값 반환. */
    abstract fun extractMetric(landmarks: List<NormalizedLandmark>): Float?

    /** 현재 프레임에서 오류 감지. null이면 오류 없음, 문자열이면 오류 메시지. */
    abstract fun detectError(landmarks: List<NormalizedLandmark>): String?

    /** IN_MOTION 진입 기준: metric이 이 값 이상이면 동작 완료(=카운트)로 판단 */
    open fun getMotionThreshold(): Float = if (isInCalibration) 5f else prb * 0.70f

    /** RETURNING → IDLE 복귀 기준: metric이 이 값 이하이면 복귀 완료 */
    open fun getReturnThreshold(): Float = if (isInCalibration) getMotionThreshold() * 0.3f else prb * 0.25f

    /**
     * IDLE → ATTEMPTING 진입 기준: 사용자가 "시도를 시작했음"을 인지하는 임계값.
     * motion threshold의 25% 정도가 기본값. ATTEMPTING 상태에서 motion 미달로 다시 IDLE로 가면
     * 코칭 큐가 발화됨. 너무 낮으면 미세한 떨림에도 코칭 큐 오발화, 너무 높으면 시도 인식 못 함.
     */
    open fun getPartialThreshold(): Float = getMotionThreshold() * 0.25f

    /** 메트릭이 "클수록 동작이 큰" 것인지 여부. false면 "작을수록 동작이 큰" (ChairStand의 D% 등) */
    open val metricIncreasing: Boolean = true

    /**
     * 카운트 시점 옵션 — 사용자 명시 (Q3).
     *
     *   ON_MOTION_START: ATTEMPTING/IDLE → IN_MOTION 시점 (들기만 해도 +1) — #2 HipAbduction
     *   ON_DESCENT_START: IN_MOTION → RETURNING 시점 (어느 정도 내려가면 +1) — #1, #3, #4, #5, #6
     *   ON_FULL_RETURN: RETURNING → IDLE 확정 시점 (완전히 돌아오면 +1) — #7 ChairStand (임상 정의)
     */
    open val countTiming: CountTiming = CountTiming.ON_DESCENT_START

    /**
     * 첫 카운트(IN_MOTION 진입 + currentCount==1) 시점에 호출되는 hook.
     * 양측 운동(HipAbduction)에서 lockedSide 결정에 사용.
     */
    open fun onFirstCount(landmarks: List<NormalizedLandmark>) {}

    /** 디버그 로그 태그. null이면 로그 비활성. 서브클래스에서 override (예: "CalfDebug").
     *  설정값이 있으면 매 30 frame(~1초)마다 logcat에 [tag] 로그 출력. */
    protected open val debugTag: String? = null
    private var debugFrameCounter = 0

    /** ATTEMPTING 진입 기준 충족? (partial threshold 이상으로 metric이 진행) */
    private fun meetsPartialThreshold(metric: Float, threshold: Float): Boolean {
        return if (metricIncreasing) metric >= threshold else metric <= threshold
    }

    /** ATTEMPTING에서 IDLE로 복귀 기준 충족? (partial threshold 미만으로 떨어짐 = 시도 포기) */
    private fun fellBelowPartialThreshold(metric: Float, threshold: Float): Boolean {
        return if (metricIncreasing) metric < threshold else metric > threshold
    }

    // ─── 공용 로직 ───

    override fun setPRB(prbValue: Float) {
        prb = prbValue
        isInCalibration = !PRBManager.isCalibrated(prbValue)
        if (isInCalibration) {
            calibrationReps = 0
            calibrationPeak = Float.NaN
        }
    }

    override fun processLandmarks(landmarks: List<NormalizedLandmark>): FrameResult {
        if (landmarks.size < 33) {
            return FrameResult(currentCount, false, false, null, state = state)
        }

        val rawMetric = extractMetric(landmarks)
            ?: return FrameResult(currentCount, false, false, null, state = state)

        val metric = smoother.smooth(rawMetric)
        val errorMsg = detectError(landmarks)
        val hasError = errorMsg != null
        val nowMs = System.currentTimeMillis()

        var countIncremented = false
        var reportedError: String? = null
        var coachingCueTriggered = false  // 이 frame에서 시도 실패 발생?
        var calibrationRepCompletedThisFrame = false

        val motionThreshold = getMotionThreshold()
        val returnThreshold = getReturnThreshold()
        val partialThreshold = getPartialThreshold()

        val stateBefore = state

        when (state) {
            EngineState.IDLE -> {
                // 쿨다운 체크 — 직전 카운트 이후 짧은 시간 내 재진입 차단
                if (nowMs - lastCountTimeMs < MIN_COOLDOWN_MS) {
                    // 쿨다운 중 — 상태 전환 안 함
                } else if (meetsMotionThreshold(metric, motionThreshold)) {
                    // 매우 빠른 동작 — partial 단계 지나서 바로 motion threshold 도달
                    transitionToInMotion(hasError, metric)
                    if (!isInCalibration && countTiming == CountTiming.ON_MOTION_START) {
                        // ON_MOTION_START: 진입 시점에 즉시 카운트 (HipAbduction)
                        currentCount++
                        countIncremented = true
                        if (currentCount == 1) onFirstCount(landmarks)
                        if (hasErrorThisRep) {
                            reportedError = errorMsg
                        }
                        lastCountTimeMs = nowMs
                        debugTag?.let { Log.d(it, "★ COUNT++ at IDLE→IN_MOTION (direct) count=$currentCount metric=%.3f motThr=%.3f".format(metric, motionThreshold)) }
                    }
                    // ON_DESCENT_START / ON_FULL_RETURN: 나중 단계에서 카운트
                } else if (meetsPartialThreshold(metric, partialThreshold)) {
                    // 시도 시작 — partial 도달
                    state = EngineState.ATTEMPTING
                    hasErrorThisRep = hasError
                    attemptingPeakMetric = metric  // ATTEMPTING peak 추적 시작
                    if (isInCalibration) {
                        calibrationPeak = selectPeak(calibrationPeak, metric)
                    }
                }
            }
            EngineState.ATTEMPTING -> {
                if (hasError) hasErrorThisRep = true
                // ATTEMPTING 동안 peak metric 추적 (실패 시 coachingCue 의미 있는지 판단)
                attemptingPeakMetric = selectPeak(attemptingPeakMetric, metric)
                if (isInCalibration) {
                    calibrationPeak = selectPeak(calibrationPeak, metric)
                }

                if (meetsMotionThreshold(metric, motionThreshold)) {
                    // motion threshold 도달
                    state = EngineState.IN_MOTION
                    attemptingPeakMetric = Float.NaN  // 성공한 시도 → 리셋
                    if (!isInCalibration && countTiming == CountTiming.ON_MOTION_START) {
                        // ON_MOTION_START: 진입 시점에 즉시 카운트 (HipAbduction)
                        currentCount++
                        countIncremented = true
                        if (currentCount == 1) onFirstCount(landmarks)
                        if (hasErrorThisRep) {
                            reportedError = errorMsg ?: detectError(landmarks)
                        }
                        lastCountTimeMs = nowMs
                        debugTag?.let { Log.d(it, "★ COUNT++ at ATTEMPTING→IN_MOTION count=$currentCount metric=%.3f motThr=%.3f".format(metric, motionThreshold)) }
                    }
                    // ON_DESCENT_START / ON_FULL_RETURN: 다음 단계에서 카운트
                } else if (fellBelowPartialThreshold(metric, partialThreshold)) {
                    // 시도 실패 — motion 못 도달하고 partial 미만으로 떨어짐
                    // coachingCue는 사용자가 의미 있게 시도했을 때만 (peak가 motionThr의 50% 이상)
                    // → 살짝 움직이고 멈춘 경우엔 발화 안 함 (사용자 혼란 방지)
                    if (hasErrorThisRep) {
                        reportedError = errorMsg ?: detectError(landmarks)
                    } else {
                        val peakRatio = if (!attemptingPeakMetric.isNaN() && motionThreshold != 0f) {
                            if (metricIncreasing) attemptingPeakMetric / motionThreshold
                            else motionThreshold / attemptingPeakMetric  // metricIncreasing=false: smaller = closer to motion
                        } else 0f
                        if (peakRatio >= 0.5f) {
                            // 의미 있는 시도(50%+ 도달)였는데 부족 → 코칭 큐
                            coachingCueTriggered = true
                        }
                        // 그 외 작은 움직임은 무시
                    }
                    hasErrorThisRep = false
                    attemptingPeakMetric = Float.NaN  // 다음 시도 위해 리셋
                    state = EngineState.IDLE
                }
            }
            EngineState.IN_MOTION -> {
                if (hasError) hasErrorThisRep = true
                if (isInCalibration) {
                    calibrationPeak = selectPeak(calibrationPeak, metric)
                }

                // 내려오기 시작 감지
                if (meetsReturnThreshold(metric, returnThreshold)) {
                    state = EngineState.RETURNING
                    // ON_DESCENT_START: 내려오기 시작 시점에 카운트 (#1, #3, #4, #5, #6)
                    if (!isInCalibration && countTiming == CountTiming.ON_DESCENT_START) {
                        currentCount++
                        countIncremented = true
                        if (currentCount == 1) onFirstCount(landmarks)
                        if (hasErrorThisRep) {
                            reportedError = errorMsg ?: detectError(landmarks)
                        }
                        lastCountTimeMs = nowMs
                        debugTag?.let { Log.d(it, "★ COUNT++ at IN_MOTION→RETURNING (descent start) count=$currentCount metric=%.3f retThr=%.3f".format(metric, returnThreshold)) }
                    }
                }
            }
            EngineState.RETURNING -> {
                // 복귀가 확실한지 2번째 확인
                if (meetsReturnThreshold(metric, returnThreshold)) {
                    // 복귀 확정
                    if (isInCalibration) {
                        calibrationReps++
                        calibrationRepCompletedThisFrame = true
                        if (calibrationReps >= 2) {
                            measuredCalibrationPRB = calibrationPeak
                            prb = calibrationPeak
                            isInCalibration = false
                        }
                    } else if (countTiming == CountTiming.ON_FULL_RETURN) {
                        // ChairStand: 완전 복귀 시점에 카운트 (임상 정의 = 일어선 횟수)
                        currentCount++
                        countIncremented = true
                        if (currentCount == 1) onFirstCount(landmarks)
                        if (hasErrorThisRep) {
                            reportedError = errorMsg ?: detectError(landmarks)
                        }
                        lastCountTimeMs = nowMs
                        debugTag?.let { Log.d(it, "★ COUNT++ at RETURNING→IDLE confirmed count=$currentCount metric=%.3f retThr=%.3f".format(metric, returnThreshold)) }
                    }
                    // ON_MOTION_START: IN_MOTION 진입 시 이미 카운트
                    // ON_DESCENT_START: IN_MOTION→RETURNING 시점에 이미 카운트
                    hasErrorThisRep = false
                    state = EngineState.IDLE
                } else {
                    // 다시 동작 중으로 복귀 (가짜 복귀)
                    state = EngineState.IN_MOTION
                }
            }
            EngineState.CALIBRATING -> {
                // 표시용 상태 — 상태 머신에서 직접 사용하지 않음
            }
        }

        // 상태 전이 발생 시 즉시 로그 (count 증가 진단용 — 어떤 전이가 +1을 만드는지 추적)
        if (debugTag != null && state != stateBefore) {
            Log.d(debugTag, "→ TRANSITION $stateBefore → $state metric=%.3f motThr=%.3f partThr=%.3f retThr=%.3f cooldownLeft=%d count=$currentCount".format(
                metric, motionThreshold, partialThreshold, returnThreshold,
                (MIN_COOLDOWN_MS - (nowMs - lastCountTimeMs)).coerceAtLeast(0L)))
        }

        val displayState = if (isInCalibration) EngineState.CALIBRATING else state

        // ─── 디버그 로그 (서브클래스가 debugTag 설정 시 매 30 frame마다 1줄) ───
        debugTag?.let { tag ->
            debugFrameCounter++
            if (debugFrameCounter % 30 == 0) {
                val visLK = landmarks[LandmarkIndex.LEFT_KNEE].visibility().orElse(0f)
                val visRK = landmarks[LandmarkIndex.RIGHT_KNEE].visibility().orElse(0f)
                val visLA = landmarks[LandmarkIndex.LEFT_ANKLE].visibility().orElse(0f)
                val visRA = landmarks[LandmarkIndex.RIGHT_ANKLE].visibility().orElse(0f)
                val visLH = landmarks[LandmarkIndex.LEFT_HEEL].visibility().orElse(0f)
                val visRH = landmarks[LandmarkIndex.RIGHT_HEEL].visibility().orElse(0f)
                Log.d(tag,
                    "f=$debugFrameCounter state=$state count=$currentCount/$targetCount calib=$isInCalibration " +
                    "raw=%.3f sm=%.3f mThr=%.3f pThr=%.3f rThr=%.3f prb=%.3f timing=$countTiming ".format(
                        rawMetric, metric, motionThreshold, partialThreshold, returnThreshold, prb) +
                    "visKnee(L/R)=%.2f/%.2f visAnkle(L/R)=%.2f/%.2f visHeel(L/R)=%.2f/%.2f ".format(
                        visLK, visRK, visLA, visRA, visLH, visRH) +
                    "errMsg=$errorMsg cueFired=$coachingCueTriggered countInc=$countIncremented"
                )
            }
        }

        return FrameResult(
            count = currentCount,
            isCountIncremented = countIncremented,
            hasError = hasErrorThisRep && !isInCalibration,
            // 매 frame errorMsg를 전달 (기존: 카운트 시점에만). ExerciseFragment의 4초 쿨다운으로 적정 빈도 보장.
            // 잘못된 다리/자세 같은 즉각 피드백이 운동 진행 중에도 발화 가능.
            errorMessage = reportedError ?: errorMsg,
            isCoachingCue = coachingCueTriggered,
            coachingCueMessage = if (coachingCueTriggered) coachingCueMessage else null,
            currentMetric = metric,
            state = displayState,
            calibrationRepCompleted = calibrationRepCompletedThisFrame,
            calibrationReps = calibrationReps
        )
    }

    private fun transitionToInMotion(hasError: Boolean, metric: Float) {
        state = EngineState.IN_MOTION
        hasErrorThisRep = hasError
        if (isInCalibration) {
            calibrationPeak = selectPeak(calibrationPeak, metric)
        }
    }

    /** 메트릭이 동작 시작 기준을 충족하는지 */
    private fun meetsMotionThreshold(metric: Float, threshold: Float): Boolean {
        return if (metricIncreasing) metric >= threshold else metric <= threshold
    }

    /** 메트릭이 복귀 기준을 충족하는지 */
    private fun meetsReturnThreshold(metric: Float, threshold: Float): Boolean {
        return if (metricIncreasing) metric <= threshold else metric >= threshold
    }

    /** 캘리브레이션 피크 갱신. 첫 capture(NaN)면 candidate 채택. */
    private fun selectPeak(current: Float, candidate: Float): Float {
        if (current.isNaN()) return candidate
        return if (metricIncreasing) maxOf(current, candidate) else minOf(current, candidate)
    }

    override fun reset() {
        currentCount = 0
        state = EngineState.IDLE
        hasErrorThisRep = false
        calibrationReps = 0
        calibrationPeak = Float.NaN
        attemptingPeakMetric = Float.NaN
        lastCountTimeMs = 0L
        smoother.reset()
    }

    override fun debugForceCount() {
        currentCount++
    }

    // ─── 좌/우 랜드마크 선택 헬퍼 ───

    /** 가시성 높은 쪽의 hip-knee-ankle 트리플 반환 */
    protected fun getHipKneeAnkle(
        landmarks: List<NormalizedLandmark>, side: Side
    ): Triple<NormalizedLandmark, NormalizedLandmark, NormalizedLandmark> {
        return if (side == Side.LEFT) {
            Triple(landmarks[LandmarkIndex.LEFT_HIP], landmarks[LandmarkIndex.LEFT_KNEE], landmarks[LandmarkIndex.LEFT_ANKLE])
        } else {
            Triple(landmarks[LandmarkIndex.RIGHT_HIP], landmarks[LandmarkIndex.RIGHT_KNEE], landmarks[LandmarkIndex.RIGHT_ANKLE])
        }
    }

    /** 가시성 높은 쪽의 heel, footIndex 페어 반환 */
    protected fun getHeelFoot(
        landmarks: List<NormalizedLandmark>, side: Side
    ): Pair<NormalizedLandmark, NormalizedLandmark> {
        return if (side == Side.LEFT) {
            Pair(landmarks[LandmarkIndex.LEFT_HEEL], landmarks[LandmarkIndex.LEFT_FOOT_INDEX])
        } else {
            Pair(landmarks[LandmarkIndex.RIGHT_HEEL], landmarks[LandmarkIndex.RIGHT_FOOT_INDEX])
        }
    }
}
