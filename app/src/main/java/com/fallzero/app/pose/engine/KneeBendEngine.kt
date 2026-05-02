package com.fallzero.app.pose.engine

import android.util.Log
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
    override val coachingCueMessage = "무릎을 더 굽혀주세요."
    override val debugTag = "KneeBendDebug"

    private var kneeBendDebugCounter = 0

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        // 양쪽 다리 모두 계산 후 더 큰 flexion 채택 (Q4 fix).
        // pickVisibleSide는 카메라 쪽 다리만 picking → 사용자가 반대편(visibility 낮은) 다리 굽히면 못 잡음.
        val (lHip, lKnee, lAnkle) = getHipKneeAnkle(landmarks, Side.LEFT)
        val (rHip, rKnee, rAnkle) = getHipKneeAnkle(landmarks, Side.RIGHT)
        val leftFlexion = (180f - AngleCalculator.calculateAngle(lHip, lKnee, lAnkle)).coerceAtLeast(0f)
        val rightFlexion = (180f - AngleCalculator.calculateAngle(rHip, rKnee, rAnkle)).coerceAtLeast(0f)

        // 둘 다 visibility 너무 낮으면 null
        val lKneeVis = lKnee.visibility().orElse(0f)
        val rKneeVis = rKnee.visibility().orElse(0f)
        if (lKneeVis < 0.2f && rKneeVis < 0.2f) return null

        val flexion = maxOf(leftFlexion, rightFlexion)
        val flexedSide = if (leftFlexion >= rightFlexion) Side.LEFT else Side.RIGHT

        kneeBendDebugCounter++
        if (kneeBendDebugCounter % 30 == 0) {
            val sbu = SBUCalculator.calculate(landmarks)
            Log.d("KneeBendDebug",
                "RAW side=$flexedSide LEFT(flex=%.1f°,vis=%.2f) RIGHT(flex=%.1f°,vis=%.2f) sbu=%.4f maxFlex=%.1f".format(
                    leftFlexion, lKneeVis, rightFlexion, rKneeVis, sbu, flexion))
        }
        return flexion
    }

    /** OVER_BEND_DEG 이상 굽히면 "너무 굽히지 마세요" 경고. 살짝 굽히기 의도에서 deep squat 방지.
     *  사용자 명시: 50°는 너무 빡빡 → 70°로 완화 (적절한 굽힘 범위 30~60° 보장). */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val (lHip, lKnee, lAnkle) = getHipKneeAnkle(landmarks, Side.LEFT)
        val (rHip, rKnee, rAnkle) = getHipKneeAnkle(landmarks, Side.RIGHT)
        val leftFlexion = (180f - AngleCalculator.calculateAngle(lHip, lKnee, lAnkle)).coerceAtLeast(0f)
        val rightFlexion = (180f - AngleCalculator.calculateAngle(rHip, rKnee, rAnkle)).coerceAtLeast(0f)
        val maxFlex = maxOf(leftFlexion, rightFlexion)
        return if (maxFlex >= OVER_BEND_DEG) "무릎을 너무 굽히지 마세요" else null
    }

    private val OVER_BEND_DEG = 80f

    override val metricIncreasing = true
    // 미니 스쿼트: 직립 ~0~5°, 살짝 굽히면 ~30°. PRB 캡 35° 적용 — 사용자가 calibration 시
    // 깊은 스쿼트(60°+)를 해도 PRB는 35°로 제한해서 본 운동 motionThr=24.5°가 되도록.
    // "살짝 굽히기" 임상 정의에 맞는 임계값 유지.
    private val MAX_PRB = 35f
    override fun getMotionThreshold() = if (isInCalibration) 25f
        else maxOf(prb.coerceAtMost(MAX_PRB) * 0.70f, 20f)
    override fun getReturnThreshold() = if (isInCalibration) 10f else 12f
}
