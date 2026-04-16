package com.fallzero.app.pose.engine

import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 #5: 발끝 들기 (Toe Raise / Dorsiflexion)
 * 양발 발끝을 들어 올렸다 내리기. 측면 촬영.
 * 메트릭: (y_heel - y_footIndex) / SBU (발끝 들림 정도)
 */
class ToeRaiseEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "발끝 들기"

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_HEEL, LandmarkIndex.RIGHT_HEEL)
            ?: return null
        val (heel, footIndex) = getHeelFoot(landmarks, side)
        return (heel.y() - footIndex.y()) / sbu
    }

    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE)
            ?: return null
        val (hip, knee, ankle) = getHipKneeAnkle(landmarks, side)
        val kneeAngle = AngleCalculator.calculateAngle(hip, knee, ankle)
        val kneeFlexion = (180f - kneeAngle).coerceAtLeast(0f)
        return if (kneeFlexion > 15f) "무릎을 펴주세요" else null
    }

    override val metricIncreasing = true
    override fun getMotionThreshold() = if (isInCalibration) 0.04f else maxOf(prb * 0.70f, 0.03f)
    override fun getReturnThreshold() = if (isInCalibration) 0.012f else prb * 0.30f
}
