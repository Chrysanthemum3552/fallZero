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

    /** 양측 운동 — lockedSide 패턴 (Q6 wrong-leg detection).
     *  첫 굽힘 시 자동 잠금 → onSideSwitch에서 flip → 잠긴 쪽만 측정.
     *  잘못된 쪽이 1회 완료(굽힘 후 펴짐 = 1 cycle) 시에만 경고 — 값 튐 방어 (사용자 명시). */
    private var lockedSide: Side? = null
    private val LOCK_THRESHOLD = 25f
    // wrong-leg 1 cycle 추적: 잘못된 쪽이 LOCK_THRESHOLD 넘었다가 RETURN_THRESHOLD 아래로 복귀 시 1회 완료
    private var wrongLegPeak = 0f
    private val WRONG_LEG_RETURN = 10f
    // 매 frame 좌/우 flexion 캐시 — extractMetric에서 계산 후 detectError가 재사용 (calculateAngle 중복 호출 절감)
    private var lastLeftFlexion = 0f
    private var lastRightFlexion = 0f

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        // 양쪽 다리 모두 계산 — 사용자가 카메라 반대편(visibility 낮은) 다리를 굽혀도 감지
        val (lHip, lKnee, lAnkle) = getHipKneeAnkle(landmarks, Side.LEFT)
        val (rHip, rKnee, rAnkle) = getHipKneeAnkle(landmarks, Side.RIGHT)
        val leftAngle = AngleCalculator.calculateAngle(lHip, lKnee, lAnkle)
        val rightAngle = AngleCalculator.calculateAngle(rHip, rKnee, rAnkle)
        val leftFlexion = (180f - leftAngle).coerceAtLeast(0f)
        val rightFlexion = (180f - rightAngle).coerceAtLeast(0f)
        // detectError가 재사용
        lastLeftFlexion = leftFlexion
        lastRightFlexion = rightFlexion

        val lKneeVis = lKnee.visibility().orElse(0f)
        val rKneeVis = rKnee.visibility().orElse(0f)
        if (lKneeVis < 0.2f && rKneeVis < 0.2f) return null

        // 첫 잠금 — 어느 한쪽이 LOCK_THRESHOLD 도달 시
        if (lockedSide == null) {
            if (leftFlexion >= LOCK_THRESHOLD || rightFlexion >= LOCK_THRESHOLD) {
                lockedSide = if (leftFlexion >= rightFlexion) Side.LEFT else Side.RIGHT
                Log.d("KneeFlexionDebug", "▶ lockedSide=$lockedSide (L=%.1f° R=%.1f°)".format(leftFlexion, rightFlexion))
            }
        }

        val flexion = when (lockedSide) {
            Side.LEFT -> leftFlexion
            Side.RIGHT -> rightFlexion
            null -> maxOf(leftFlexion, rightFlexion)
        }
        val flexedSide = lockedSide ?: if (leftFlexion >= rightFlexion) Side.LEFT else Side.RIGHT

        // 통계 업데이트
        maxFlexionLeft = max(maxFlexionLeft, leftFlexion)
        maxFlexionRight = max(maxFlexionRight, rightFlexion)
        maxAnkleYLeft = max(maxAnkleYLeft, lAnkle.y()); minAnkleYLeft = min(minAnkleYLeft, lAnkle.y())
        maxAnkleYRight = max(maxAnkleYRight, rAnkle.y()); minAnkleYRight = min(minAnkleYRight, rAnkle.y())

        // 추가 진단 로그
        kneeFlexionDebugCounter++
        if (kneeFlexionDebugCounter % 30 == 0) {
            val sbu = SBUCalculator.calculate(landmarks)
            // ankleY 변동 = 발이 위로 차올라가면 ankleY 감소 (smaller y = higher in image)
            val ankleYRangeLeft = maxAnkleYLeft - minAnkleYLeft
            val ankleYRangeRight = maxAnkleYRight - minAnkleYRight
            Log.d("KneeFlexionDebug",
                "RAW flexedSide=$flexedSide LEFT(rawAngle=%.1f°,flex=%.1f°,vis=%.2f) RIGHT(rawAngle=%.1f°,flex=%.1f°,vis=%.2f) sbu=%.4f".format(
                    leftAngle, leftFlexion, lKneeVis,
                    rightAngle, rightFlexion, rKneeVis, sbu))
            Log.d("KneeFlexionDebug",
                "POSE Lhip=(%.3f,%.3f) Lknee=(%.3f,%.3f) Lankle=(%.3f,%.3f) Rhip=(%.3f,%.3f) Rknee=(%.3f,%.3f) Rankle=(%.3f,%.3f)".format(
                    lHip.x(), lHip.y(), lKnee.x(), lKnee.y(), lAnkle.x(), lAnkle.y(),
                    rHip.x(), rHip.y(), rKnee.x(), rKnee.y(), rAnkle.x(), rAnkle.y()))
            Log.d("KneeFlexionDebug",
                "STATS maxFlex L=%.1f° R=%.1f° (motionThr=%.1f°) ankleY range L=[%.4f~%.4f]Δ%.4f R=[%.4f~%.4f]Δ%.4f — 발 차올리면 ankleY↓(Δ↑) + flex↑ 발생해야 함".format(
                    maxFlexionLeft, maxFlexionRight, getMotionThreshold(),
                    minAnkleYLeft, maxAnkleYLeft, ankleYRangeLeft,
                    minAnkleYRight, maxAnkleYRight, ankleYRangeRight))
        }
        return flexion
    }

    /** 잠긴 쪽 못 굽히고 반대쪽이 1회 완료(굽힘→복귀) 시에만 "다른쪽 다리" 발화. 골반 검사도 유지.
     *  최적화: extractMetric에서 계산한 lastLeft/RightFlexion 재사용 — calculateAngle 2회 절감/frame */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val locked = lockedSide
        if (locked != null) {
            val leftFlex = lastLeftFlexion
            val rightFlex = lastRightFlexion
            val (lockedFlex, otherFlex) = if (locked == Side.LEFT) leftFlex to rightFlex else rightFlex to leftFlex
            // wrong-leg 1 cycle 추적: 굽힘 → 펴짐 = 1회 완료 → 발화 1회
            if (otherFlex >= LOCK_THRESHOLD) {
                wrongLegPeak = max(wrongLegPeak, otherFlex)
            }
            // 잠긴 쪽이 거의 안 움직였는데 반대쪽만 1 cycle 완료 → 경고
            if (wrongLegPeak >= LOCK_THRESHOLD && otherFlex < WRONG_LEG_RETURN && lockedFlex < 15f) {
                wrongLegPeak = 0f  // 발화 1회 후 리셋
                return "다른쪽 다리로 해주세요"
            }
        }
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val hipShift = abs(
            landmarks[LandmarkIndex.LEFT_HIP].y() - landmarks[LandmarkIndex.RIGHT_HIP].y()
        ) / sbu
        return if (hipShift >= 0.08f) "골반이 흔들리지 않도록 해주세요" else null
    }

    override val metricIncreasing = true
    // 슬굴곡(180-angle): 직립 시 ~0~10°, 굽히면 ~60~90°.
    // 훈련 모드 임계값:
    //  motionThr: maxOf(prb*0.55, 40°) — 깊은 calibration peak에서도 moderate flex로 트리거 가능
    //  retThr 12 → 20 (훈련만): 발이 거의 펴진 상태(20° 이내)면 즉시 RETURN → 카운트 빠르게.
    //    smoother alpha=0.5에서 sm이 retThr까지 내려가는 시간 단축. 1초 lag → ~0.3초.
    override fun getMotionThreshold() = if (isInCalibration) 30f else maxOf(prb * 0.55f, 40f)
    override fun getReturnThreshold() = if (isInCalibration) 10f else 20f

    override fun onSideSwitch() {
        lockedSide = when (lockedSide) {
            Side.LEFT -> Side.RIGHT
            Side.RIGHT -> Side.LEFT
            null -> null
        }
        wrongLegPeak = 0f  // 양측 전환 시 wrong-leg 추적 리셋
        Log.d("KneeFlexionDebug", "▶ onSideSwitch → lockedSide=$lockedSide")
    }

    override fun reset() {
        super.reset()
        kneeFlexionDebugCounter = 0
        maxFlexionLeft = 0f
        maxFlexionRight = 0f
        maxAnkleYLeft = -Float.MAX_VALUE; minAnkleYLeft = Float.MAX_VALUE
        maxAnkleYRight = -Float.MAX_VALUE; minAnkleYRight = Float.MAX_VALUE
        wrongLegPeak = 0f
        // dominantSide는 reset에서 보존 — onSideSwitch에서만 flip
    }
}
