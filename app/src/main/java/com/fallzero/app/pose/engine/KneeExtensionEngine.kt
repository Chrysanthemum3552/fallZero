package com.fallzero.app.pose.engine

import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

/**
 * 운동 #1: 대퇴사두근 강화 (Knee Extension)
 * 의자에 앉아 한쪽 다리를 앞으로 쭉 뻗어 올렸다 내리기. 측면 촬영.
 * 메트릭: hip-knee-ankle 각도 (직립 ~180°, 구부리면 ~90°, 펴면 ~160°+)
 * 좌/우 다리 자동 감지 (가시성 높은 쪽 사용)
 */
class KneeExtensionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "대퇴사두근 강화"
    override val coachingCueMessage = "다리를 더 쭉 펴 주세요."

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE)
            ?: return null
        val (hip, knee, ankle) = getHipKneeAnkle(landmarks, side)
        return AngleCalculator.calculateAngle(hip, knee, ankle)
    }

    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val shoulderMidX = (landmarks[LandmarkIndex.LEFT_SHOULDER].x() + landmarks[LandmarkIndex.RIGHT_SHOULDER].x()) / 2
        val hipMidX = (landmarks[LandmarkIndex.LEFT_HIP].x() + landmarks[LandmarkIndex.RIGHT_HIP].x()) / 2
        val shoulderMidY = (landmarks[LandmarkIndex.LEFT_SHOULDER].y() + landmarks[LandmarkIndex.RIGHT_SHOULDER].y()) / 2
        val hipMidY = (landmarks[LandmarkIndex.LEFT_HIP].y() + landmarks[LandmarkIndex.RIGHT_HIP].y()) / 2
        val tiltDeg = Math.toDegrees(
            kotlin.math.atan2((shoulderMidX - hipMidX).toDouble(), (hipMidY - shoulderMidY).toDouble())
        ).toFloat()
        return if (abs(tiltDeg) > 15f) "상체를 바르게 세워주세요" else null
    }

    override val metricIncreasing = true
    // 무릎 신전: 앉아서 무릎 각도가 ~90° → 다리 펴면 ~160°+로 증가.
    // 캘리브레이션 시 130° 이상이면 동작 시작, 100° 이하면 복귀로 판단.
    override fun getMotionThreshold() = if (isInCalibration) 130f else maxOf(prb * 0.85f, 130f)
    override fun getReturnThreshold() = if (isInCalibration) 100f else 110f
}
