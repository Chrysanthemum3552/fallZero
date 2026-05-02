package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.data.algorithm.BalanceProgressionManager
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.MetricSmoother
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.max

/**
 * 균형 검사/훈련 엔진.
 *
 * 동작 방식:
 * 1. 사용자가 올바른 자세를 취하면 (poseValid=true) 타이머 시작
 * 2. 자세 유지 중 균형을 잃으면 (sway 초과 또는 자세 이탈) → 즉시 타이머 중단, 현재까지 시간 기록
 * 3. 다시 자세를 잡으면 타이머 재시작
 * 4. "최대 연속 유지 시간 ≥ 목표 시간"이면 통과
 *
 * 상태:
 *   IDLE (자세 대기) → IN_MOTION (안정 유지 타이머 진행 중) → IDLE (균형 잃음 또는 통과)
 */
class BalanceEngine(
    override val targetCount: Int = 1,
    private val stage: Int = 1,
    private val overrideTargetTimeSec: Float? = null
) : ExerciseEngine {

    override val exerciseName = "균형 훈련"
    override var currentCount = 0; private set
    override var isInCalibration = false; private set
    override var measuredCalibrationPRB = 0f; private set

    private var state = EngineState.IDLE
    private var stableStartTimeMs = 0L

    /** stage=4(한 발 서기) 양측 운동 — 어느 발을 들었는지 잠금. 운동 #8에서만 의미.
     *  null = 자동 감지 모드 (첫 frame에서 들린 발 결정), Side.LEFT/RIGHT = 잠긴 다리.
     *  잠긴 후 반대 발 들면 errorMessage 발화 + isStable=false. */
    private var lockedLiftSide: Side? = null
    // 잘못된 발이 들린 시작 시각 (ms). 1초 이상 지속 시에만 경고 — 값 튐 방어 (사용자 명시).
    private var wrongFootSinceMs: Long = 0L
    private val WRONG_FOOT_HOLD_MS = 1000L

    private val targetTimeSec get() = overrideTargetTimeSec ?: BalanceProgressionManager.getTargetTime(stage)

    private val smoother = MetricSmoother(alpha = 0.25f)

    // 단계별 sway 임계값: 발 간격이 좁을수록 자연 sway가 커지므로 단계별 차등 적용
    // 실측 데이터 기반:
    //   일반 직립(발 벌림): rawA ≈ 0.08~0.12
    //   발 모음(stage 1):  rawA ≈ 0.19~0.22 (자연 sway)
    //   탠덤(stage 3):     rawA ≈ 0.15~0.25 (자연 sway)
    //   한 발(stage 4):    rawA ≈ 0.20~0.30 (자연 sway)
    //   → 임계값은 "자연 sway + 마진"으로 설정, "진짜 균형 잃음(0.35+)"과 구분
    private fun getSwayThreshold(): Float = when (stage) {
        1 -> 0.28f   // 발 모음: 자연 sway ~0.20 + 마진
        2 -> 0.30f   // 반탠덤: 자연 sway ~0.22 + 마진
        3 -> 0.33f   // 탠덤: 자연 sway ~0.25 + 마진
        4 -> 0.45f   // 한 발: 자연 sway ~0.28 + 큰 마진
        else -> 0.28f
    }

    // 최대 연속 유지 시간 (리포트용)
    var bestHoldTimeSec: Float = 0f; private set

    // 안정 상태 확인: 최소 3프레임 연속 (노이즈 필터)
    private var consecutiveStableFrames = 0
    private val MIN_STABLE_FRAMES = 3

    private var debugFrameCount = 0

    override fun setPRB(prbValue: Float) { /* 균형 훈련은 PRB 미사용 */ }

    override fun processLandmarks(landmarks: List<NormalizedLandmark>): FrameResult {
        if (landmarks.size < 33) return FrameResult(currentCount, false, false, null, state = state)

        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return FrameResult(currentCount, false, false, null, state = state)

        val leftAnkleX = landmarks[LandmarkIndex.LEFT_ANKLE].x()
        val rightAnkleX = landmarks[LandmarkIndex.RIGHT_ANKLE].x()
        val leftAnkleY = landmarks[LandmarkIndex.LEFT_ANKLE].y()
        val rightAnkleY = landmarks[LandmarkIndex.RIGHT_ANKLE].y()

        // ── 1. 지지 다리 선택 (sway 측정용) ──
        val ankleYDiff = abs(leftAnkleY - rightAnkleY)
        val side = if (ankleYDiff > 0.05f) {
            if (leftAnkleY > rightAnkleY) Side.LEFT else Side.RIGHT
        } else {
            AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_ANKLE, LandmarkIndex.RIGHT_ANKLE)
                ?: Side.LEFT
        }
        val ankleIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_ANKLE else LandmarkIndex.RIGHT_ANKLE
        val kneeIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_KNEE else LandmarkIndex.RIGHT_KNEE
        val hipIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_HIP else LandmarkIndex.RIGHT_HIP

        val rawAScore = (abs(landmarks[ankleIdx].x() - landmarks[kneeIdx].x()) +
                abs(landmarks[kneeIdx].x() - landmarks[hipIdx].x())) / sbu
        val aScore = smoother.smooth(rawAScore)

        // ── 2. 자세 검증 (해당 단계의 발 배치가 맞는지) ──
        val footXNorm = abs(leftAnkleX - rightAnkleX) / sbu
        val footYNorm = ankleYDiff / sbu

        var poseValid = true
        var poseHint: String? = null

        // 실측 데이터 기반 임계값:
        //   일반 직립(나란히): footX ≈ 0.28~0.33, footY ≈ 0.00~0.02
        //   반탠덤 자세:       footX ≈ 0.03~0.10, footY ≈ 0.15~0.25
        //   탠덤 자세:         footX ≈ 0.01~0.10, footY ≈ 0.20~0.40
        //   한 발 서기:        footY ≈ 0.55~0.92
        when (stage) {
            1 -> {
                // 두 발 나란히: 발 좌우 간격이 SBU의 50% 이하
                if (footXNorm > 0.50f) {
                    poseValid = false
                    poseHint = "발을 더 모아주세요"
                }
            }
            2 -> {
                // 반탠덤: 한 발이 다른 발보다 앞에 있어야 (footY > 4%)
                if (footYNorm < 0.04f) {
                    poseValid = false
                    poseHint = "한쪽 발을 반보 앞에 놓아주세요"
                }
            }
            3 -> {
                // 탠덤 (일렬): 한 발이 다른 발 바로 앞에 + 발이 일렬
                // 실측: 실제 탠덤 footX≈0.01~0.10, footY≈0.20~0.40
                //        그냥 한 발 앞으로 footX≈0.49~0.75, footY≈0.23~0.31
                if (footYNorm < 0.10f) {
                    poseValid = false
                    poseHint = "한쪽 발을 다른 발 앞에 놓아주세요"
                } else if (footXNorm > 0.35f) {
                    poseValid = false
                    poseHint = "발을 일렬로 맞춰주세요"
                }
            }
            4 -> {
                // 한 발 서기: 한쪽 발이 확실히 들려야 (footY > 6%)
                if (footYNorm < 0.06f) {
                    poseValid = false
                    poseHint = "한쪽 발을 들어주세요"
                    wrongFootSinceMs = 0L  // 발 안 들면 잘못된 발 추적 리셋
                } else {
                    // 들린 발 = ankleY가 작은 쪽 (이미지 좌표 기준 위쪽)
                    val liftedSide = if (leftAnkleY < rightAnkleY) Side.LEFT else Side.RIGHT
                    when (lockedLiftSide) {
                        null -> {
                            // 자동 감지: 첫 들린 발로 잠금 (운동 #8 좌→우 흐름의 시작)
                            lockedLiftSide = liftedSide
                            wrongFootSinceMs = 0L
                            Log.d("BalanceDebug", "▶ stage4 lockedLiftSide=$liftedSide (auto-detected)")
                        }
                        else -> {
                            if (liftedSide != lockedLiftSide) {
                                // 잘못된 발 — 1초 이상 지속 시에만 경고 (사용자 명시)
                                if (wrongFootSinceMs == 0L) {
                                    wrongFootSinceMs = System.currentTimeMillis()
                                }
                                if (System.currentTimeMillis() - wrongFootSinceMs >= WRONG_FOOT_HOLD_MS) {
                                    poseValid = false
                                    poseHint = "반대쪽 발로 들어주세요"
                                }
                                // 1초 미만 = 값 튐으로 간주, 경고 X
                            } else {
                                wrongFootSinceMs = 0L  // 올바른 발 들고 있으면 리셋
                            }
                        }
                    }
                }
            }
        }

        // ── 3. 안정성 판단: 자세가 맞고 + sway가 임계값 이내 ──
        val swayThreshold = getSwayThreshold()
        val isStable = poseValid && aScore < swayThreshold
        val nowMs = System.currentTimeMillis()

        var countIncremented = false
        var errorMsg: String? = null
        var capturedElapsedOnIncrement = -1f

        when (state) {
            EngineState.IDLE -> {
                if (isStable) {
                    consecutiveStableFrames++
                    if (consecutiveStableFrames >= MIN_STABLE_FRAMES) {
                        state = EngineState.IN_MOTION
                        stableStartTimeMs = nowMs
                    }
                } else {
                    consecutiveStableFrames = 0
                }
            }
            EngineState.IN_MOTION -> {
                if (!isStable) {
                    // 즉시 타이머 중단 + 현재까지 시간 기록
                    val heldSec = (nowMs - stableStartTimeMs) / 1000f
                    bestHoldTimeSec = max(bestHoldTimeSec, heldSec)

                    errorMsg = if (!poseValid) poseHint ?: "자세를 확인해주세요"
                              else "균형을 잡아주세요"

                    state = EngineState.IDLE
                    stableStartTimeMs = 0L
                    consecutiveStableFrames = 0
                } else {
                    val elapsedSec = (nowMs - stableStartTimeMs) / 1000f
                    if (elapsedSec >= targetTimeSec) {
                        capturedElapsedOnIncrement = elapsedSec
                        bestHoldTimeSec = max(bestHoldTimeSec, elapsedSec)
                        currentCount++
                        countIncremented = true
                        state = EngineState.IDLE
                        stableStartTimeMs = 0L
                        consecutiveStableFrames = 0
                    }
                }
            }
            else -> {}
        }

        // 디버그 로그
        debugFrameCount++
        if (debugFrameCount % 30 == 0) {
            Log.d("BalanceDebug",
                "stage=$stage side=$side rawA=%.3f sA=%.3f thr=%.2f pose=%b footX=%.2f footY=%.2f stable=$isStable state=$state best=%.1f elapsed=%.1f"
                    .format(rawAScore, aScore, swayThreshold, poseValid, footXNorm, footYNorm,
                        bestHoldTimeSec, if (stableStartTimeMs > 0) (nowMs - stableStartTimeMs) / 1000f else 0f))
        }

        val reportedElapsedSec = when {
            capturedElapsedOnIncrement >= 0f -> capturedElapsedOnIncrement
            stableStartTimeMs > 0L -> (nowMs - stableStartTimeMs) / 1000f
            else -> 0f
        }
        // errorMsg가 없어도 자세 힌트가 있으면 전달 (IDLE 상태에서 왜 카운트 안 되는지 피드백)
        val finalErrorMsg = errorMsg
            ?: if (!poseValid) poseHint
            else if (!isStable && aScore >= swayThreshold) "균형을 잡아주세요"
            else null

        return FrameResult(
            count = currentCount,
            isCountIncremented = countIncremented,
            hasError = !isStable,
            errorMessage = finalErrorMsg,
            isCoachingCue = false,
            currentMetric = reportedElapsedSec,
            state = state
        )
    }

    override fun reset() {
        currentCount = 0
        state = EngineState.IDLE
        stableStartTimeMs = 0L
        consecutiveStableFrames = 0
        bestHoldTimeSec = 0f
        smoother.reset()
        debugFrameCount = 0
        // lockedLiftSide는 reset에서 보존 — onSideSwitch로만 flip (운동 #8의 좌→우 전환)
    }

    /** 양측 운동 #8(한 발 서기)에서 좌→우 전환 시 호출 — lockedLiftSide flip.
     *  Side가 null이면(자동 감지 전 호출) 그대로 둠 — 첫 발 감지 시 새 lockedLiftSide 결정. */
    override fun onSideSwitch() {
        lockedLiftSide = when (lockedLiftSide) {
            Side.LEFT -> Side.RIGHT
            Side.RIGHT -> Side.LEFT
            null -> null
        }
        wrongFootSinceMs = 0L
        Log.d("BalanceDebug", "▶ onSideSwitch → lockedLiftSide=$lockedLiftSide")
    }

    override fun debugForceCount() { currentCount++ }
}
