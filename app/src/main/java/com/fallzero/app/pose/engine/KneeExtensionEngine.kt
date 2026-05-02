package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.Side
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 #1: 대퇴사두근 강화 (Knee Extension)
 * 의자에 앉아 한쪽 다리를 앞으로 쭉 뻗어 올렸다 내리기. 측면 촬영.
 *
 * 양측 운동 — lockedSide 패턴 (Q6):
 *   첫 펴기에서 자동 잠금 → onSideSwitch에서 flip → 잠긴 쪽만 측정.
 *   잠긴 쪽이 굽혀있는데 반대쪽만 펴지면 "다른쪽 다리로 해주세요" 발화.
 */
class KneeExtensionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "대퇴사두근 강화"
    override val coachingCueMessage = "다리를 더 펴주세요."

    private var lockedSide: Side? = null
    private val LOCK_THRESHOLD = 130f  // 신전 130° 이상 시 잠금
    // wrong-leg 1 cycle 추적: 잘못된 쪽이 LOCK_THRESHOLD 도달했다가 굽힘 자세로 복귀 시 1회 완료
    private var wrongLegReachedExtension = false
    private val WRONG_LEG_FLEXED_BACK = 120f  // 다시 이 각도 미만으로 굽혀졌으면 복귀
    // 매 frame 좌/우 angle 캐시 — extractMetric → detectError 재사용 (calculateAngle 2회/frame 절감)
    private var lastLeftAngle = 0f
    private var lastRightAngle = 0f

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val (lHip, lKnee, lAnkle) = getHipKneeAnkle(landmarks, Side.LEFT)
        val (rHip, rKnee, rAnkle) = getHipKneeAnkle(landmarks, Side.RIGHT)
        val leftAngle = AngleCalculator.calculateAngle(lHip, lKnee, lAnkle)
        val rightAngle = AngleCalculator.calculateAngle(rHip, rKnee, rAnkle)
        lastLeftAngle = leftAngle
        lastRightAngle = rightAngle
        val lKneeVis = lKnee.visibility().orElse(0f)
        val rKneeVis = rKnee.visibility().orElse(0f)
        if (lKneeVis < 0.2f && rKneeVis < 0.2f) return null

        // 첫 잠금 — 어느 한쪽이 LOCK_THRESHOLD 도달 시
        if (lockedSide == null) {
            if (leftAngle >= LOCK_THRESHOLD || rightAngle >= LOCK_THRESHOLD) {
                lockedSide = if (leftAngle >= rightAngle) Side.LEFT else Side.RIGHT
                Log.d("KneeExtensionDebug", "▶ lockedSide=$lockedSide (L=%.1f° R=%.1f°)".format(leftAngle, rightAngle))
            }
        }

        return when (lockedSide) {
            Side.LEFT -> leftAngle
            Side.RIGHT -> rightAngle
            null -> maxOf(leftAngle, rightAngle)
        }
    }

    /** 잘못된 쪽이 1회 완료(완전히 폈다가 다시 굽힘) 시에만 "다른쪽 다리" 발화 — 값 튐 방어.
     *  최적화: extractMetric 캐시값 재사용 — calculateAngle 2회/frame 절감 */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val locked = lockedSide ?: return null
        val leftAngle = lastLeftAngle
        val rightAngle = lastRightAngle
        val (lockedAngle, otherAngle) = if (locked == Side.LEFT) leftAngle to rightAngle else rightAngle to leftAngle
        // wrong-leg 1 cycle 추적
        if (otherAngle >= LOCK_THRESHOLD) wrongLegReachedExtension = true
        if (wrongLegReachedExtension && otherAngle < WRONG_LEG_FLEXED_BACK && lockedAngle < 120f) {
            wrongLegReachedExtension = false
            return "다른쪽 다리로 해주세요"
        }
        return null
    }

    override fun onSideSwitch() {
        lockedSide = when (lockedSide) {
            Side.LEFT -> Side.RIGHT
            Side.RIGHT -> Side.LEFT
            null -> null
        }
        wrongLegReachedExtension = false  // 양측 전환 시 wrong-leg 추적 리셋
        Log.d("KneeExtensionDebug", "▶ onSideSwitch → lockedSide=$lockedSide")
    }

    override val metricIncreasing = true
    override fun getMotionThreshold() = if (isInCalibration) 130f else maxOf(prb * 0.85f, 130f)
    override fun getReturnThreshold() = if (isInCalibration) 100f else 110f
}
