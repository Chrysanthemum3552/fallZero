package com.fallzero.app.pose.engine

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
     * 첫 카운트(IN_MOTION 진입 + currentCount==1) 시점에 호출되는 hook.
     * 양측 운동(HipAbduction)에서 lockedSide 결정에 사용.
     */
    open fun onFirstCount(landmarks: List<NormalizedLandmark>) {}

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

        val motionThreshold = getMotionThreshold()
        val returnThreshold = getReturnThreshold()
        val partialThreshold = getPartialThreshold()

        when (state) {
            EngineState.IDLE -> {
                // 쿨다운 체크 — 직전 카운트 이후 짧은 시간 내 재진입 차단
                if (nowMs - lastCountTimeMs < MIN_COOLDOWN_MS) {
                    // 쿨다운 중 — 상태 전환 안 함
                } else if (meetsMotionThreshold(metric, motionThreshold)) {
                    // 매우 빠른 동작 — partial 단계 지나서 바로 motion threshold 도달
                    transitionToInMotion(hasError, metric)
                    if (!isInCalibration) {
                        currentCount++
                        countIncremented = true
                        if (currentCount == 1) onFirstCount(landmarks)
                        if (hasErrorThisRep) {
                            reportedError = errorMsg
                        }
                        lastCountTimeMs = nowMs
                    }
                } else if (meetsPartialThreshold(metric, partialThreshold)) {
                    // 시도 시작 — partial 도달
                    state = EngineState.ATTEMPTING
                    hasErrorThisRep = hasError
                    if (isInCalibration) {
                        calibrationPeak = selectPeak(calibrationPeak, metric)
                    }
                }
            }
            EngineState.ATTEMPTING -> {
                if (hasError) hasErrorThisRep = true
                if (isInCalibration) {
                    calibrationPeak = selectPeak(calibrationPeak, metric)
                }

                if (meetsMotionThreshold(metric, motionThreshold)) {
                    // motion threshold 도달 — 카운트 진행
                    state = EngineState.IN_MOTION
                    if (!isInCalibration) {
                        currentCount++
                        countIncremented = true
                        if (currentCount == 1) onFirstCount(landmarks)
                        if (hasErrorThisRep) {
                            reportedError = errorMsg ?: detectError(landmarks)
                        }
                        lastCountTimeMs = nowMs
                    }
                } else if (fellBelowPartialThreshold(metric, partialThreshold)) {
                    // 시도 실패 — motion 못 도달하고 partial 미만으로 떨어짐
                    if (!isInCalibration) {
                        if (hasErrorThisRep) {
                            // 자세 오류 있었음 (예: 잘못된 다리) → errorMessage 우선 발화
                            reportedError = errorMsg ?: detectError(landmarks)
                        } else {
                            // 단순히 부족하게 들었다 내림 → 코칭 큐
                            coachingCueTriggered = true
                        }
                    }
                    hasErrorThisRep = false
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
                }
            }
            EngineState.RETURNING -> {
                // 복귀가 확실한지 2번째 확인
                if (meetsReturnThreshold(metric, returnThreshold)) {
                    // 복귀 확정 → 사이클 종료 (카운트는 IN_MOTION 진입 시 이미 됨)
                    if (isInCalibration) {
                        calibrationReps++
                        if (calibrationReps >= 2) {
                            measuredCalibrationPRB = calibrationPeak
                            prb = calibrationPeak
                            isInCalibration = false
                        }
                    }
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

        val displayState = if (isInCalibration) EngineState.CALIBRATING else state

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
            state = displayState
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
