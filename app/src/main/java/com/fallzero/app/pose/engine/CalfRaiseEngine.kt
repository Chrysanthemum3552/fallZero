package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

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

    // PDF §8: "몸통 앞뒤 반동" 검출용 — 측면 자세에서 shoulder.x가 baseline 대비 일정량 이상
    // 변동하면 몸이 앞뒤로 흔들리는 보상 동작. 첫 30프레임만 baseline EMA로 안정화.
    // 무릎 굽힘 검출은 측면 view 각도 추정 부정확으로 기존 주석대로 의도적 미포함.
    private var shoulderXBaseline: Float = Float.NaN
    private var swayBaselineFrameCount = 0
    private val SWAY_BASELINE_FRAMES = 30      // ~1초 (30fps 기준)
    private val SWAY_THRESHOLD = 0.25f         // SBU의 25% — 발뒤꿈치 들 때 자연스러운 상체 보상 동작 추가 허용 (노인 대상 추가 완화 0.18→0.25)

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

        // 10th percentile baseline — outlier에 강함.
        // sort O(n log n) → quickselect O(n) — 결과 동일, ~6배 빠름 (n=90 기준).
        val effectiveSize = if (bufferFilled) WINDOW_SIZE else bufferIdx
        val baseline = if (effectiveSize >= 10) {
            val pctIdx = (effectiveSize * 0.10f).toInt().coerceAtLeast(0)
            com.fallzero.app.pose.nthSmallest(ratioBuffer.copyOf(effectiveSize), pctIdx, 0, effectiveSize - 1)
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

    /**
     * 무릎 펴기 검사는 측면 view 각도 추정 부정확으로 의도적 미포함 (기존 설계 유지).
     * PDF §8의 "몸통 앞뒤 반동"만 보강 — shoulder.x가 baseline 대비 SBU의 SWAY_THRESHOLD 초과하면 경고.
     */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_SHOULDER, LandmarkIndex.RIGHT_SHOULDER)
            ?: return null
        val shoulderIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_SHOULDER else LandmarkIndex.RIGHT_SHOULDER
        val shoulderX = landmarks[shoulderIdx].x()

        if (swayBaselineFrameCount < SWAY_BASELINE_FRAMES) {
            shoulderXBaseline = if (shoulderXBaseline.isNaN()) shoulderX
                                else shoulderXBaseline * 0.7f + shoulderX * 0.3f
            swayBaselineFrameCount++
            return null
        }
        val deviationRatio = abs(shoulderX - shoulderXBaseline) / sbu
        return if (deviationRatio > SWAY_THRESHOLD) "몸이 흔들리지 않도록 해주세요" else null
    }

    override val metricIncreasing = true
    // inactivity 임계값 — signal 0~0.10 범위.
    // 0.005는 천천히 발뒤꿈치 들 때 frame 간 변화량이 미달해 4초 timeout으로 갑자기 종료되는 버그 원인.
    // 0.003으로 완화 — 정상 운동 페이스에서 충분히 변화가 감지되도록.
    override val movementThreshold = 0.003f
    // 작은 ROM + 측면 촬영 + 노인 사용자의 느린 페이스 — 4초 timeout이 조기 종료 유발.
    // 8초로 완화: 한 사이클(2~3초) × 2회분 grace 확보, 진짜 운동 중단(>8초 정지)만 종료.
    override val inactivityTimeoutMs = 8000L
    // **고정 임계값** (PRB 기반 X) — calf raise는 사용자별 ROM 차이 적음, 해부학 기반 고정값.
    //   고령층 작은 ROM도 카운트 가능하도록 motionThr=0.030 설정.
    //   PRB outlier로 임계값 폭주하던 문제 해결.
    override fun getMotionThreshold() = 0.030f
    override fun getReturnThreshold() = 0.012f

    /** 막대기 시각화(읽기 전용) — lastMetric을 progress로 변환. 좌표 판정 로직과 무관. */
    override fun getGuide(landmarks: List<NormalizedLandmark>): com.fallzero.app.ui.overlay.ExerciseGuide? {
        if (isInCalibration) return null
        val motThr = getMotionThreshold(); val retThr = getReturnThreshold()
        val gap = motThr - retThr
        val progress = if (gap > 0f) ((lastMetric - retThr) / gap).coerceIn(0f, 1f) else 0f
        return com.fallzero.app.ui.overlay.ExerciseGuide.Bar(
            progress = progress, vertical = true,
            fillDirection = com.fallzero.app.ui.overlay.ExerciseGuide.FillDirection.UP,
            label = "$exerciseName 진행도", justReached = progress >= 1f
        )
    }

    /** 발뒷꿈치 들기는 ROM이 매우 작아 IN_MOTION→RETURNING 시점이 noisy.
     *  내려와서 IDLE 도달(=완전 복귀)할 때 카운트 — ToeRaise/ChairStand와 동일. */
    override val countTiming = CountTiming.ON_FULL_RETURN

    override fun reset() {
        super.reset()
        calfDebugCounter = 0
        for (i in ratioBuffer.indices) ratioBuffer[i] = 0f
        bufferIdx = 0
        bufferFilled = false
        shoulderXBaseline = Float.NaN
        swayBaselineFrameCount = 0
    }
}
