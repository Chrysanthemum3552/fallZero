package com.fallzero.app.pose.engine

import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

/**
 * 운동 #2: 고관절 외전 (Hip Abduction)
 * 서서 한쪽 다리를 옆으로 들어 올렸다 내리기. 정면 촬영.
 * 메트릭: 무릎-엉덩이 선과 수직선의 각도 (외전 각도)
 *
 * 양측 lockedSide 처리 (정면 운동 — 두 다리 모두 카메라에 보임):
 *  - 첫 카운트(IN_MOTION 진입) 시점에 lockedSide 결정 = 그 시점 큰 angle의 다리
 *  - 이후 lockedSide 다리만 측정 (반대 다리 들면 angle 무시 + errorMessage 발화)
 *  - 두 번째 섹션 시작 시 onSideSwitch()로 lockedSide flip
 */
class HipAbductionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "고관절 외전"
    override val coachingCueMessage = "다리를 더 높이 들어주세요."

    /** 한 발 옆으로 들기 — 들 때 +1 (사용자 명시) */
    override val countTiming = CountTiming.ON_MOTION_START

    private var lockedSide: Side? = null
    // wrong-leg 1 cycle 추적: 잘못된 쪽이 motionThr 도달 후 returnThr 아래로 복귀 시 1회 완료
    private var wrongLegPeak = 0f

    init {
        // 빠른 반응을 위해 smoother alpha를 0.5로 (기본 0.3 → 카운트 발화 ~200ms 단축)
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.5f)
    }

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float {
        val leftAngle = calcAbductionAngle(landmarks, Side.LEFT)
        val rightAngle = calcAbductionAngle(landmarks, Side.RIGHT)
        // lockedSide 결정 전: 둘 중 큰 값 사용 (어느 다리든 들면 동작 시작 인지)
        // lockedSide 결정 후: 그 다리 angle만 사용 (다른 다리 들어도 카운트 안 됨)
        return when (lockedSide) {
            Side.LEFT -> leftAngle
            Side.RIGHT -> rightAngle
            null -> maxOf(leftAngle, rightAngle)
        }
    }

    /** 첫 카운트 시점에 lockedSide 결정 — BaseRepEngine이 ATTEMPTING → IN_MOTION 전이 시 호출 */
    override fun onFirstCount(landmarks: List<NormalizedLandmark>) {
        if (lockedSide == null) {
            val leftAngle = calcAbductionAngle(landmarks, Side.LEFT)
            val rightAngle = calcAbductionAngle(landmarks, Side.RIGHT)
            lockedSide = if (leftAngle >= rightAngle) Side.LEFT else Side.RIGHT
        }
    }

    /** 두 번째 섹션 시작 시 호출 — lockedSide 반대로 flip */
    override fun onSideSwitch() {
        lockedSide = when (lockedSide) {
            Side.LEFT -> Side.RIGHT
            Side.RIGHT -> Side.LEFT
            null -> null
        }
        wrongLegPeak = 0f  // wrong-leg 추적 리셋
    }

    /**
     * 외전 각도: 골반 중심에서 본 무릎의 측방 변위.
     * 직립 시 수직선과 다리(엉덩이→무릎)가 이루는 각도.
     * x 차이가 양수든 음수든 옆으로 들면 절댓값이 커짐.
     */
    private fun calcAbductionAngle(landmarks: List<NormalizedLandmark>, side: Side): Float {
        val hipIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_HIP else LandmarkIndex.RIGHT_HIP
        val kneeIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_KNEE else LandmarkIndex.RIGHT_KNEE
        val hip = landmarks[hipIdx]
        val knee = landmarks[kneeIdx]
        // visibility check 제거: MediaPipe는 occluded 키포인트도 추정값을 제공하므로
        // 다리를 들어 가시성이 일시적으로 떨어져도 angle 계산을 유지해야 false transition 방지
        val dx = abs(knee.x() - hip.x())
        val dy = abs(knee.y() - hip.y())
        if (dy < 1e-4f) return 0f
        return Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
    }

    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        // wrong-leg 1 cycle 추적: 반대쪽이 motionThr 넘었다가 returnThr 아래로 복귀 시 발화
        if (lockedSide != null) {
            val leftAngle = calcAbductionAngle(landmarks, Side.LEFT)
            val rightAngle = calcAbductionAngle(landmarks, Side.RIGHT)
            val (lockedAngle, otherAngle) = if (lockedSide == Side.LEFT)
                leftAngle to rightAngle else rightAngle to leftAngle
            val motionThr = getMotionThreshold()
            val retThr = getReturnThreshold()
            if (otherAngle >= motionThr) {
                wrongLegPeak = kotlin.math.max(wrongLegPeak, otherAngle)
            }
            // 반대쪽이 1 cycle 완료 + lockedSide는 거의 안 움직였음 → 경고
            if (wrongLegPeak >= motionThr && otherAngle < retThr && lockedAngle < retThr) {
                wrongLegPeak = 0f
                return "반대쪽 다리로 해주세요."
            }
        }

        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val leftHipY = landmarks[LandmarkIndex.LEFT_HIP].y()
        val rightHipY = landmarks[LandmarkIndex.RIGHT_HIP].y()
        val pelvisTiltRatio = abs(leftHipY - rightHipY) / sbu
        val pelvisTiltDeg = Math.toDegrees(kotlin.math.atan2(pelvisTiltRatio.toDouble(), 1.0)).toFloat()
        return if (pelvisTiltDeg >= 10f) "골반이 기울지 않게 해주세요." else null
    }

    override val metricIncreasing = true
    // 외전 각도: 직립 시 ~5°, 옆으로 들면 ~25~40°.
    override fun getMotionThreshold() = if (isInCalibration) 18f else maxOf(prb * 0.70f, 15f)
    override fun getReturnThreshold() = if (isInCalibration) 8f else 10f
}
