package com.fallzero.app.pose.engine

import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

/**
 * 운동 #3: 슬굴곡근 강화 (Knee Flexion / Hamstring Curl)
 * 서서 한쪽 무릎을 뒤로 굽혔다 펴기. 측면 촬영.
 * 메트릭: 180° - hip-knee-ankle 각도 (직립 ≈ 0°, 굴곡 시 증가)
 */
class KneeFlexionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "슬굴곡근 강화"

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE)
            ?: return null
        val (hip, knee, ankle) = getHipKneeAnkle(landmarks, side)
        val rawAngle = AngleCalculator.calculateAngle(hip, knee, ankle)
        return (180f - rawAngle).coerceAtLeast(0f)
    }

    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val hipShift = abs(
            landmarks[LandmarkIndex.LEFT_HIP].y() - landmarks[LandmarkIndex.RIGHT_HIP].y()
        ) / sbu
        return if (hipShift >= 0.08f) "골반이 흔들리지 않도록 해주세요" else null
    }

    override val metricIncreasing = true
    // 슬굴곡(180-angle): 직립 시 ~0~10°, 굽히면 ~60~90°.
    override fun getMotionThreshold() = if (isInCalibration) 30f else maxOf(prb * 0.70f, 25f)
    override fun getReturnThreshold() = if (isInCalibration) 12f else 15f
}
