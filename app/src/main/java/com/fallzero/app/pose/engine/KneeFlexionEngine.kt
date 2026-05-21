package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 운동 #3: 슬굴곡근 강화 (Knee Flexion / Hamstring Curl)
 * 서서 한쪽 무릎을 뒤로 굽혔다 펴기. 측면 촬영.
 * 메트릭: 180° - hip-knee-ankle 각도 (직립 ≈ 0°, 굴곡 시 증가)
 *
 * 사용자 명시: 측면 운동은 좌우 구별 논리 제거 — 좌/우 어느 다리든 굽히면 카운트.
 * 사용자 신뢰 기반 (Fragment의 handleSideSwitch가 안내만 함).
 */
class KneeFlexionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "슬굴곡근 강화"
    override val coachingCueMessage = "발을 더 들어주세요."
    override val debugTag = "KneeFlexionDebug"

    init {
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.5f)
    }

    private var kneeFlexionDebugCounter = 0
    private var maxFlexionLeft = 0f
    private var maxFlexionRight = 0f
    private var maxAnkleYLeft = -Float.MAX_VALUE
    private var minAnkleYLeft = Float.MAX_VALUE
    private var maxAnkleYRight = -Float.MAX_VALUE
    private var minAnkleYRight = Float.MAX_VALUE

    // PDF §8 — "허벅지 앞으로 들림" 보상 동작 검출용 baseline.
    // 측면 자세 standing pose에서 knee.y가 기준선. 굽힘 동작 중 knee가 baseline 대비 위로
    // 올라가면(=thigh가 들리면) 보상. calibration 중에는 baseline 안 잡음 (굽힘 연습으로 노이즈).
    private var kneeYBaseline: Float = Float.NaN
    private var kneeYBaselineFrameCount = 0
    private val KNEE_BASELINE_FRAMES = 30          // ~1초
    private val THIGH_LIFT_THRESHOLD = 0.05f       // SBU의 5%

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        // 양쪽 다리 모두 계산 — 어느 쪽이든 굽혀지면 감지 (좌우 구별 X)
        val (lHip, lKnee, lAnkle) = getHipKneeAnkle(landmarks, Side.LEFT)
        val (rHip, rKnee, rAnkle) = getHipKneeAnkle(landmarks, Side.RIGHT)
        val leftAngle = AngleCalculator.calculateAngle(lHip, lKnee, lAnkle)
        val rightAngle = AngleCalculator.calculateAngle(rHip, rKnee, rAnkle)
        val leftFlexion = (180f - leftAngle).coerceAtLeast(0f)
        val rightFlexion = (180f - rightAngle).coerceAtLeast(0f)

        val lKneeVis = lKnee.visibility().orElse(0f)
        val rKneeVis = rKnee.visibility().orElse(0f)
        if (lKneeVis < 0.2f && rKneeVis < 0.2f) return null

        val flexion = maxOf(leftFlexion, rightFlexion)
        val flexedSide = if (leftFlexion >= rightFlexion) Side.LEFT else Side.RIGHT

        // 통계 업데이트
        maxFlexionLeft = max(maxFlexionLeft, leftFlexion)
        maxFlexionRight = max(maxFlexionRight, rightFlexion)
        maxAnkleYLeft = max(maxAnkleYLeft, lAnkle.y()); minAnkleYLeft = min(minAnkleYLeft, lAnkle.y())
        maxAnkleYRight = max(maxAnkleYRight, rAnkle.y()); minAnkleYRight = min(minAnkleYRight, rAnkle.y())

        // 추가 진단 로그
        kneeFlexionDebugCounter++
        if (kneeFlexionDebugCounter % 30 == 0) {
            val sbu = SBUCalculator.calculate(landmarks)
            val ankleYRangeLeft = maxAnkleYLeft - minAnkleYLeft
            val ankleYRangeRight = maxAnkleYRight - minAnkleYRight
            Log.d("KneeFlexionDebug",
                "RAW flexedSide=$flexedSide LEFT(rawAngle=%.1f°,flex=%.1f°,vis=%.2f) RIGHT(rawAngle=%.1f°,flex=%.1f°,vis=%.2f) sbu=%.4f".format(
                    leftAngle, leftFlexion, lKneeVis,
                    rightAngle, rightFlexion, rKneeVis, sbu))
            Log.d("KneeFlexionDebug",
                "STATS maxFlex L=%.1f° R=%.1f° (motionThr=%.1f°) ankleY range L=Δ%.4f R=Δ%.4f".format(
                    maxFlexionLeft, maxFlexionRight, getMotionThreshold(),
                    ankleYRangeLeft, ankleYRangeRight))
        }
        return flexion
    }

    /**
     * 검출 순서:
     *   1) 골반 좌우 흔들림 (기존)
     *   2) 허벅지 앞으로 들림 — knee.y가 baseline 대비 위로 올라감 (PDF §8 신규)
     * 좌우 구별 논리는 사용자 명시로 제거.
     */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null

        // 1) 골반 좌우 흔들림 (기존)
        val hipShift = abs(
            landmarks[LandmarkIndex.LEFT_HIP].y() - landmarks[LandmarkIndex.RIGHT_HIP].y()
        ) / sbu
        if (hipShift >= 0.08f) return "골반이 흔들리지 않도록 해주세요"

        // 2) 허벅지 들림 — calibration 중에는 baseline 신뢰 불가하므로 패스.
        if (isInCalibration) return null
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE)
            ?: return null
        val kneeIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_KNEE else LandmarkIndex.RIGHT_KNEE
        val kneeY = landmarks[kneeIdx].y()

        if (kneeYBaselineFrameCount < KNEE_BASELINE_FRAMES) {
            kneeYBaseline = if (kneeYBaseline.isNaN()) kneeY else kneeYBaseline * 0.7f + kneeY * 0.3f
            kneeYBaselineFrameCount++
            return null
        }
        // image y는 위로 갈수록 작아짐 → baseline - 현재 > 0 = 무릎이 위로 들림.
        val liftRatio = (kneeYBaseline - kneeY) / sbu
        return if (liftRatio > THIGH_LIFT_THRESHOLD) "허벅지를 들지 마세요" else null
    }

    override val metricIncreasing = true
    // 슬굴곡(180-angle): 직립 시 ~0~10°, 굽히면 ~60~90°.
    override fun getMotionThreshold() = if (isInCalibration) 30f else maxOf(prb * 0.55f, 40f)
    // 복귀 기준 완화 — 발을 완전히 내려놓기 어려운 점 고려 (20° → 28°). 28° 이내면 카운트.
    override fun getReturnThreshold() = if (isInCalibration) 10f else 28f

    override fun reset() {
        super.reset()
        kneeFlexionDebugCounter = 0
        maxFlexionLeft = 0f
        maxFlexionRight = 0f
        maxAnkleYLeft = -Float.MAX_VALUE; minAnkleYLeft = Float.MAX_VALUE
        maxAnkleYRight = -Float.MAX_VALUE; minAnkleYRight = Float.MAX_VALUE
        kneeYBaseline = Float.NaN
        kneeYBaselineFrameCount = 0
    }
}
