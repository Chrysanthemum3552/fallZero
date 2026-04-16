package com.fallzero.app.pose.engine

import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

/**
 * 운동 #6: 무릎 굽히기 (Mini Squat / Knee Bend)
 * 양발 어깨 너비로 벌리고 무릎을 살짝 굽혔다 펴기. 측면 촬영.
 * 메트릭: 180° - hip-knee-ankle 각도 (직립 ≈ 0°, 굽힘 시 증가)
 */
class KneeBendEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "무릎 굽히기"

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
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE)
            ?: return null
        val kneeIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_KNEE else LandmarkIndex.RIGHT_KNEE
        val footIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_FOOT_INDEX else LandmarkIndex.RIGHT_FOOT_INDEX
        val kneeX = landmarks[kneeIdx].x()
        val toeX = landmarks[footIdx].x()
        val kneeOvershoot = abs(kneeX - toeX)
        return if (kneeOvershoot > sbu * 0.1f && state == EngineState.IN_MOTION)
            "무릎이 발끝 앞으로 너무 나가지 않도록 해주세요" else null
    }

    override val metricIncreasing = true
    // 미니 스쿼트: 직립 ~0~5°, 살짝 굽히면 ~30~60°.
    override fun getMotionThreshold() = if (isInCalibration) 25f else maxOf(prb * 0.70f, 20f)
    override fun getReturnThreshold() = if (isInCalibration) 10f else 12f
}
