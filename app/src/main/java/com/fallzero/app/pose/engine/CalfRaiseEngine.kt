package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 #4: 발뒤꿈치 들기 (Calf Raise)
 * 양발 발뒤꿈치를 들어 올렸다 내리기. 측면 촬영.
 *
 * 메트릭: (ankle.y - heel.y) / SBU
 *   - toe 의존 제거 — 사용자가 카메라에 가까이 서면 toe가 화면 밖으로 잘려 추정값이 부정확.
 *   - ankle과 heel은 거의 항상 화면 안에 들어옴.
 *   - 서있을 때: heel이 ankle 아래(image y 더 큼) → 메트릭 ~ -0.10 (음수)
 *   - heel 들면: heel이 ankle 가까이/위로 올라감 → 메트릭 ~ 0 ~ +0.10 (양수)
 *   - 0 부근이 자연스러운 hysteresis 분리점.
 *
 * 양쪽 중 heel visibility 높은 쪽 자동 선택.
 */
class CalfRaiseEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "발뒤꿈치 들기"
    override val coachingCueMessage = "발뒷꿈치를 더 들어주세요."
    override val debugTag = "CalfDebug"

    init {
        // 빠른 사이클 대응 — alpha 0.8로 smoother를 매우 반응적으로 (pulse 추적)
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.8f)
    }

    private var calfDebugCounter = 0
    // ratio = (toe.y - heel.y) / sbu — 발 안에서의 상대적 위치 (toe=pivot, heel=움직임).
    // baseline = 최근 90 프레임의 10th percentile (통계적 outlier-robust).
    //   median(50%)보다 낮은 값 → "가장 펴진 자세" 가까이지만 single-frame outlier에 무영향.
    private val WINDOW_SIZE = 90
    private val ratioBuffer = FloatArray(WINDOW_SIZE)
    private var bufferIdx = 0
    private var bufferFilled = false

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_HEEL, LandmarkIndex.RIGHT_HEEL)
            ?: return null
        val ankle = if (side == Side.LEFT) landmarks[LandmarkIndex.LEFT_ANKLE] else landmarks[LandmarkIndex.RIGHT_ANKLE]
        val (heel, footIndex) = getHeelFoot(landmarks, side)

        // ratio: heel이 toe 위로 올라가면 양수. standing 시 ~0.
        val ratio = (footIndex.y() - heel.y()) / sbu

        ratioBuffer[bufferIdx] = ratio
        bufferIdx = (bufferIdx + 1) % WINDOW_SIZE
        if (bufferIdx == 0) bufferFilled = true

        // 10th percentile baseline — outlier에 강함
        val effectiveSize = if (bufferFilled) WINDOW_SIZE else bufferIdx
        val baseline = if (effectiveSize >= 10) {
            val sorted = ratioBuffer.copyOf(effectiveSize).also { it.sort() }
            val pctIdx = (effectiveSize * 0.10f).toInt().coerceAtLeast(0)
            sorted[pctIdx]
        } else ratio  // 첫 9 프레임은 그냥 current ratio

        val signal = ratio - baseline

        // 추가 진단 로그
        calfDebugCounter++
        if (calfDebugCounter % 30 == 0) {
            Log.d("CalfDebug",
                "RAW side=$side sbu=%.4f heelY=%.4f toeY=%.4f ankleY=%.4f ratio=%.4f baseline=%.4f signal=%.4f heelVis=%.2f toeVis=%.2f".format(
                    sbu, heel.y(), footIndex.y(), ankle.y(), ratio, baseline, signal,
                    heel.visibility().orElse(0f), footIndex.visibility().orElse(0f)))
        }
        return signal
    }

    /** 무릎 펴기 검사 제거 — MediaPipe 측면 view에서 무릎 각도 추정이 부정확해 false positive 빈발.
     *  사용자 명시 — heel 들기만 측정하고 충분히 들었는지만 판단. */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? = null

    override val metricIncreasing = true
    // inactivity 임계값 — signal 0~0.10 범위 → 0.005.
    override val movementThreshold = 0.005f
    // **고정 임계값** (PRB 기반 X) — calf raise는 사용자별 ROM 차이 적음, 해부학 기반 고정값.
    //   고령층 작은 ROM도 카운트 가능하도록 motionThr=0.030 설정.
    //   PRB outlier로 임계값 폭주하던 문제 해결.
    override fun getMotionThreshold() = 0.030f
    override fun getReturnThreshold() = 0.012f

    /** 발뒷꿈치 들기는 ROM이 매우 작아 IN_MOTION→RETURNING 시점이 noisy.
     *  내려와서 IDLE 도달(=완전 복귀)할 때 카운트 — ToeRaise/ChairStand와 동일. */
    override val countTiming = CountTiming.ON_FULL_RETURN

    override fun reset() {
        super.reset()
        calfDebugCounter = 0
        for (i in ratioBuffer.indices) ratioBuffer[i] = 0f
        bufferIdx = 0
        bufferFilled = false
    }
}
