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
    // 매 frame 좌/우 flexion 캐시 — extractMetric → detectError 재사용 (calculateAngle 2회/frame 절감)
    private var lastLeftFlexion = 0f
    private var lastRightFlexion = 0f

    // PDF §8 — "무릎 과도 전방 돌출" 보상 동작. 측면 자세에서 (knee.x - ankle.x) 의 baseline
    // (standing 자세)을 잡고, 굽힘 동작 중 절대 차이가 baseline 대비 SBU의 임계값 초과 시 경고.
    // 좌우 체중 쏠림/몸통 좌우 기울임은 측면 view에서 직접 측정 불가하므로 미포함.
    private var kneeAnkleDxBaseline: Float = Float.NaN
    private var kneeForwardBaselineFrameCount = 0
    private val KNEE_FORWARD_BASELINE_FRAMES = 30
    private val KNEE_FORWARD_THRESHOLD = 0.35f  // standing baseline 대비 SBU의 35%까지 허용 (사용자 요청 추가 완화 0.25 → 0.35)

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        // 양쪽 다리 모두 계산 후 더 큰 flexion 채택 (Q4 fix).
        // pickVisibleSide는 카메라 쪽 다리만 picking → 사용자가 반대편(visibility 낮은) 다리 굽히면 못 잡음.
        val (lHip, lKnee, lAnkle) = getHipKneeAnkle(landmarks, Side.LEFT)
        val (rHip, rKnee, rAnkle) = getHipKneeAnkle(landmarks, Side.RIGHT)
        val leftFlexion = (180f - AngleCalculator.calculateAngle(lHip, lKnee, lAnkle)).coerceAtLeast(0f)
        val rightFlexion = (180f - AngleCalculator.calculateAngle(rHip, rKnee, rAnkle)).coerceAtLeast(0f)
        lastLeftFlexion = leftFlexion
        lastRightFlexion = rightFlexion

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

    /**
     * 검출 순서 (PDF §8):
     *   1) 무릎 과도 굽힘 — maxFlex ≥ OVER_BEND_DEG (기존, 사용자 명시 80°)
     *   2) 무릎 과도 전방 돌출 — (knee.x - ankle.x) 절댓값이 baseline 대비 SBU 비율 초과 (신규)
     * 최적화: extractMetric 캐시값 재사용 — calculateAngle 2회/frame 절감.
     */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val maxFlex = maxOf(lastLeftFlexion, lastRightFlexion)
        if (maxFlex >= OVER_BEND_DEG) return "무릎을 너무 굽히지 마세요"

        // 무릎 전방 돌출 — calibration 중에는 standing baseline 잡기 불안정 (사용자 굽힘 연습 중).
        if (isInCalibration) return null
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE)
            ?: return null
        val kneeIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_KNEE else LandmarkIndex.RIGHT_KNEE
        val ankleIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_ANKLE else LandmarkIndex.RIGHT_ANKLE
        val dx = landmarks[kneeIdx].x() - landmarks[ankleIdx].x()

        if (kneeForwardBaselineFrameCount < KNEE_FORWARD_BASELINE_FRAMES) {
            kneeAnkleDxBaseline = if (kneeAnkleDxBaseline.isNaN()) dx
                                  else kneeAnkleDxBaseline * 0.7f + dx * 0.3f
            kneeForwardBaselineFrameCount++
            return null
        }
        // 측면 자세는 카메라 방향에 따라 dx 부호가 달라지므로 절댓값으로 비교.
        // 굽힘 동작 중 |dx|가 standing baseline의 |dx|보다 SBU의 임계값만큼 더 커지면 무릎이 앞으로 더 나간 것.
        val forwardShift = (abs(dx) - abs(kneeAnkleDxBaseline)) / sbu
        return if (forwardShift > KNEE_FORWARD_THRESHOLD) "무릎이 너무 앞으로 나오지 않게 해주세요" else null
    }

    private val OVER_BEND_DEG = 80f

    override val metricIncreasing = true
    // 미니 스쿼트: 직립 ~0~5°, 살짝 굽히면 ~30°. PRB 캡 35° 적용 — 사용자가 calibration 시
    // 깊은 스쿼트(60°+)를 해도 PRB는 35°로 제한해서 본 운동 motionThr=24.5°가 되도록.
    // "살짝 굽히기" 임상 정의에 맞는 임계값 유지.
    private val MAX_PRB = 35f
    override fun getMotionThreshold() = if (isInCalibration) 25f
        else maxOf(prb.coerceAtMost(MAX_PRB) * 0.70f, 20f)
    // 복귀 기준 완화 — 노인 사용자가 무릎을 완전히 펴기 어려운 점 고려 (12° → 16°). 16° 이내면 카운트.
    override fun getReturnThreshold() = if (isInCalibration) 10f else 16f

    /** 막대기 시각화(읽기 전용) — lastMetric을 progress로 변환. 좌표 판정 로직과 무관. */
    override fun getGuide(landmarks: List<NormalizedLandmark>): com.fallzero.app.ui.overlay.ExerciseGuide? {
        if (isInCalibration) return null
        val motThr = getMotionThreshold(); val retThr = getReturnThreshold()
        val gap = motThr - retThr
        val progress = if (gap > 0f) ((lastMetric - retThr) / gap).coerceIn(0f, 1f) else 0f
        return com.fallzero.app.ui.overlay.ExerciseGuide.Bar(
            progress = progress, vertical = true,
            // 아래로 굽히는 운동이므로 막대기도 위→아래로 채워지게 (사용자 요청, 직관성)
            fillDirection = com.fallzero.app.ui.overlay.ExerciseGuide.FillDirection.DOWN,
            label = "$exerciseName 진행도", justReached = progress >= 1f
        )
    }

    override fun reset() {
        super.reset()
        kneeBendDebugCounter = 0
        lastLeftFlexion = 0f
        lastRightFlexion = 0f
        kneeAnkleDxBaseline = Float.NaN
        kneeForwardBaselineFrameCount = 0
    }
}
