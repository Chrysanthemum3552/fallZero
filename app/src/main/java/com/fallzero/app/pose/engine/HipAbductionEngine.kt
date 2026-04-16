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
 * 좌/우 다리 자동 감지: 두 다리 중 외전 각도가 더 큰 쪽을 사용
 */
class HipAbductionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "고관절 외전"

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float {
        // 정면 촬영: 양쪽 다리의 외전 각도를 모두 계산하고 큰 쪽 사용
        // (좌/우 어느 쪽을 들어도 자동 감지)
        val leftAngle = calcAbductionAngle(landmarks, Side.LEFT)
        val rightAngle = calcAbductionAngle(landmarks, Side.RIGHT)
        return maxOf(leftAngle, rightAngle)
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
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val leftHipY = landmarks[LandmarkIndex.LEFT_HIP].y()
        val rightHipY = landmarks[LandmarkIndex.RIGHT_HIP].y()
        val pelvisTiltRatio = abs(leftHipY - rightHipY) / sbu
        val pelvisTiltDeg = Math.toDegrees(kotlin.math.atan2(pelvisTiltRatio.toDouble(), 1.0)).toFloat()
        return if (pelvisTiltDeg >= 10f) "골반이 기울어지지 않도록 해주세요" else null
    }

    override val metricIncreasing = true
    // 외전 각도: 직립 시 ~5°, 옆으로 들면 ~25~40°.
    override fun getMotionThreshold() = if (isInCalibration) 18f else maxOf(prb * 0.70f, 15f)
    override fun getReturnThreshold() = if (isInCalibration) 8f else 10f
}
