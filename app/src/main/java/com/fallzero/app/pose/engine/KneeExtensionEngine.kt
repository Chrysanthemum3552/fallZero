package com.fallzero.app.pose.engine

import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.Side
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 #1: 대퇴사두근 강화 (Knee Extension)
 * 의자에 앉아 한쪽 다리를 앞으로 쭉 뻗어 올렸다 내리기. 측면 촬영.
 *
 * 사용자 명시: 측면 운동은 좌우 구별 논리 제거 — 좌/우 어느 다리든 펴면 카운트.
 * 사용자 신뢰 기반 (Fragment의 handleSideSwitch가 안내만 함).
 */
class KneeExtensionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "대퇴사두근 강화"
    override val coachingCueMessage = "다리를 더 펴주세요."

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val (lHip, lKnee, lAnkle) = getHipKneeAnkle(landmarks, Side.LEFT)
        val (rHip, rKnee, rAnkle) = getHipKneeAnkle(landmarks, Side.RIGHT)
        val lKneeVis = lKnee.visibility().orElse(0f)
        val rKneeVis = rKnee.visibility().orElse(0f)
        if (lKneeVis < 0.2f && rKneeVis < 0.2f) return null

        val leftAngle = AngleCalculator.calculateAngle(lHip, lKnee, lAnkle)
        val rightAngle = AngleCalculator.calculateAngle(rHip, rKnee, rAnkle)
        // 어느 쪽이든 더 펴진 쪽을 측정값으로 사용
        return maxOf(leftAngle, rightAngle)
    }

    override fun detectError(landmarks: List<NormalizedLandmark>): String? = null

    override val metricIncreasing = true
    override fun getMotionThreshold() = if (isInCalibration) 130f else maxOf(prb * 0.85f, 130f)
    override fun getReturnThreshold() = if (isInCalibration) 100f else 110f
}
