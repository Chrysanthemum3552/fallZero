package com.fallzero.app.pose.engine

import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 #4: 발뒤꿈치 들기 (Calf Raise)
 * 양발 발뒤꿈치를 들어 올렸다 내리기. 측면 촬영.
 * 메트릭: (y_footIndex - y_heel) / SBU (뒤꿈치 들림 정도)
 * 양쪽 중 가시성 높은 쪽 자동 선택.
 */
class CalfRaiseEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "발뒤꿈치 들기"

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_HEEL, LandmarkIndex.RIGHT_HEEL)
            ?: return null
        val (heel, footIndex) = getHeelFoot(landmarks, side)
        return (footIndex.y() - heel.y()) / sbu
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
    // 발뒤꿈치 들기는 SBU 대비 발높이 비율(작은 값 ~0.05~0.20).
    // PRB 기준 80% 이상 도달 → 카운트, 30% 이하로 복귀 → 사이클 완료.
    override fun getMotionThreshold() = if (isInCalibration) 0.05f else maxOf(prb * 0.70f, 0.04f)
    override fun getReturnThreshold() = if (isInCalibration) 0.015f else prb * 0.30f
}
