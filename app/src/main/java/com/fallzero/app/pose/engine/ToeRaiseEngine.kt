package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * 운동 #5: 발끝 들기 (Toe Raise / Dorsiflexion)
 * 양발 발끝을 들어 올렸다 내리기. 측면 촬영.
 * 메트릭: (y_heel - y_footIndex) / SBU (발끝 들림 정도)
 */
class ToeRaiseEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "발끝 들기"
    override val coachingCueMessage = "발끝을 더 들어주세요."
    override val debugTag = "ToeDebug"

    init {
        // 빠른 사이클 대응 — alpha 0.8로 smoother 반응 매우 빠르게 (pulse pattern 추적).
        // 기본 0.5는 5초 윈도우의 30%만 반영, 0.8은 20% 평균 → 실시간 dip 추적 가능.
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.8f)
    }

    private var toeDebugCounter = 0
    // 진단용 통계
    private var ratioMin = Float.MAX_VALUE
    private var ratioMax = -Float.MAX_VALUE
    private var signalMin = Float.MAX_VALUE
    private var signalMax = -Float.MAX_VALUE

    // ratio = (heel.y - toe.y) / sbu — 발 안에서의 상대적 위치 (heel=pivot, toe=움직임).
    // baseline = 최근 90 프레임의 10th percentile (통계적 outlier-robust).
    private val WINDOW_SIZE = 90
    private val ratioBuffer = FloatArray(WINDOW_SIZE)
    private var bufferIdx = 0
    private var bufferFilled = false

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_HEEL, LandmarkIndex.RIGHT_HEEL)
            ?: return null
        val (heel, footIndex) = getHeelFoot(landmarks, side)
        val ankle = if (side == Side.LEFT) landmarks[LandmarkIndex.LEFT_ANKLE] else landmarks[LandmarkIndex.RIGHT_ANKLE]

        // ratio: toe가 heel 위로 올라가면 양수. standing 시 ~0 또는 small negative.
        val ratio = (heel.y() - footIndex.y()) / sbu

        // 통계 업데이트
        ratioMin = min(ratioMin, ratio)
        ratioMax = max(ratioMax, ratio)

        // 발 기울기 각도
        val footDx = footIndex.x() - heel.x()
        val footDy = heel.y() - footIndex.y()
        val footTiltDeg = Math.toDegrees(atan2(footDy.toDouble(), footDx.toDouble())).toFloat()
        val knee = if (side == Side.LEFT) landmarks[LandmarkIndex.LEFT_KNEE] else landmarks[LandmarkIndex.RIGHT_KNEE]
        val ankleAngle = AngleCalculator.calculateAngle(knee, ankle, footIndex)

        ratioBuffer[bufferIdx] = ratio
        bufferIdx = (bufferIdx + 1) % WINDOW_SIZE
        if (bufferIdx == 0) bufferFilled = true

        // 5th percentile baseline — outlier에 매우 강함 + standing 자세에 가장 가까운 값 채택
        // (10th보다 낮춰 더 standing-biased — 사용자 small ROM도 신호 잘 감지)
        val effectiveSize = if (bufferFilled) WINDOW_SIZE else bufferIdx
        val baseline = if (effectiveSize >= 5) {
            val sorted = ratioBuffer.copyOf(effectiveSize).also { it.sort() }
            val pctIdx = (effectiveSize * 0.05f).toInt().coerceAtLeast(0)
            sorted[pctIdx]
        } else ratio
        val signal = ratio - baseline
        signalMin = min(signalMin, signal)
        signalMax = max(signalMax, signal)

        toeDebugCounter++
        if (toeDebugCounter % 30 == 0) {
            Log.d("ToeDebug",
                "RAW side=$side sbu=%.4f heelXY=(%.3f,%.4f) toeXY=(%.3f,%.4f) ankleXY=(%.3f,%.4f) ratio=%.4f baseline=%.4f signal=%.4f footTilt=%.1f° ankleAngle(KAT)=%.1f° heelVis=%.2f toeVis=%.2f".format(
                    sbu, heel.x(), heel.y(), footIndex.x(), footIndex.y(), ankle.x(), ankle.y(),
                    ratio, baseline, signal, footTiltDeg, ankleAngle,
                    heel.visibility().orElse(0f), footIndex.visibility().orElse(0f)))
            Log.d("ToeDebug",
                "STATS ratio range [%.4f ~ %.4f] (Δ=%.4f) signal range [%.4f ~ %.4f] (Δ=%.4f) — 발끝 들면 signal>0 (motionThr=%.4f retThr=%.4f)".format(
                    ratioMin, ratioMax, ratioMax - ratioMin,
                    signalMin, signalMax, signalMax - signalMin,
                    getMotionThreshold(), getReturnThreshold()))
        }
        return signal
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
    // inactivity 임계값
    override val movementThreshold = 0.005f
    // **고정 임계값** (PRB 기반 X) — toe raise 해부학 기반.
    //   고령층 작은 ROM 수용을 위해 motionThr 0.030 → 0.022.
    //   gap = 0.010 (hysteresis 유지).
    override fun getMotionThreshold() = 0.022f
    override fun getReturnThreshold() = 0.012f

    /** 발끝 들기는 ROM이 매우 작아 IN_MOTION→RETURNING 시점이 noisy.
     *  내려와서 IDLE 도달(=원위치 복귀)할 때 카운트 — ChairStand와 동일한 임상 정의. */
    override val countTiming = CountTiming.ON_FULL_RETURN

    override fun reset() {
        super.reset()
        toeDebugCounter = 0
        ratioMin = Float.MAX_VALUE
        ratioMax = -Float.MAX_VALUE
        signalMin = Float.MAX_VALUE
        signalMax = -Float.MAX_VALUE
        for (i in ratioBuffer.indices) ratioBuffer[i] = 0f
        bufferIdx = 0
        bufferFilled = false
    }
}
