package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

/**
 * 운동 #4: 발뒤꿈치 들기 (Calf Raise) — **정면 촬영** (측면 흔들림 회피, 사용자 요청).
 * 양발 발뒤꿈치를 들어 올렸다(까치발) 내리기.
 * 메트릭: 양발 (foot_index.y − ankle.y)/SBU 평균 = 발끝(바닥 고정) 대비 발목이 올라온 정도. "가장 평평한 자세" baseline 대비 신호.
 */
class CalfRaiseEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "발뒤꿈치 들기"
    override val coachingCueMessage = "발뒷꿈치를 더 들어주세요."
    override val debugTag = "CalfDebug"

    init {
        // 스무딩 없음 (alpha=1.0) — 발목/발끝 좌표가 안정적이라 평활 불필요 (#5와 동일, 사용자 요청).
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 1.0f)
    }

    private var calfDebugCounter = 0
    // baseline = 최근 윈도우(약 4초)의 5th percentile = "가장 평평한 자세" — 매 프레임 상대 갱신.
    private val WINDOW_SIZE = 120
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
        // 정면 전환: 발뒤꿈치를 들면(까치발) 발끝(ball)은 바닥에 고정되고 발목이 위로 올라간다.
        //   → 양발 (foot_index.y − ankle.y)/SBU 평균 = 발끝 대비 발목이 올라온 정도 (측면 heel/먼다리 가림 회피).
        val lAnkle = landmarks[LandmarkIndex.LEFT_ANKLE]; val lToe = landmarks[LandmarkIndex.LEFT_FOOT_INDEX]
        val rAnkle = landmarks[LandmarkIndex.RIGHT_ANKLE]; val rToe = landmarks[LandmarkIndex.RIGHT_FOOT_INDEX]
        val lVis = minOf(lAnkle.visibility().orElse(0f), lToe.visibility().orElse(0f))
        val rVis = minOf(rAnkle.visibility().orElse(0f), rToe.visibility().orElse(0f))
        val lRaise = (lToe.y() - lAnkle.y()) / sbu   // 까치발 들면 ankle.y↓ → 값 증가
        val rRaise = (rToe.y() - rAnkle.y()) / sbu
        val ratio = when {
            lVis > 0.3f && rVis > 0.3f -> (lRaise + rRaise) / 2f
            lVis > 0.3f -> lRaise
            rVis > 0.3f -> rRaise
            else -> return null
        }

        // baseline = 최근 윈도우(약 4초)의 5th percentile = "가장 평평한(발 디딘) 자세" — 매 프레임 상대 갱신.
        ratioBuffer[bufferIdx] = ratio
        bufferIdx = (bufferIdx + 1) % WINDOW_SIZE
        if (bufferIdx == 0) bufferFilled = true
        val effSize = if (bufferFilled) WINDOW_SIZE else bufferIdx
        val baseline = if (effSize >= 5) {
            val pctIdx = (effSize * 0.05f).toInt().coerceAtLeast(0)
            com.fallzero.app.pose.nthSmallest(ratioBuffer.copyOf(effSize), pctIdx, 0, effSize - 1)
        } else ratio

        val signal = ratio - baseline

        calfDebugCounter++
        if (calfDebugCounter % 30 == 0) {
            Log.d("CalfDebug",
                "FRONT lRaise=%.4f rRaise=%.4f vis(l=%.2f r=%.2f) ratio=%.4f baseline=%.4f signal=%.4f motThr=%.4f retThr=%.4f".format(
                    lRaise, rRaise, lVis, rVis, ratio, baseline, signal, getMotionThreshold(), getReturnThreshold()))
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
    // PRB 기반 (정면 전환, #5와 동일): 연습 2회로 "최대 까치발"(prb)을 측정 → 본운동에서 그 60% 이상 들면 1회.
    //   연습 중에는 작은 기본값으로 동작을 잡는다. (logcat 실측으로 60% 조정 가능)
    override fun getMotionThreshold() = if (isInCalibration) 0.015f else maxOf(prb * 0.60f, 0.010f)
    override fun getReturnThreshold() = if (isInCalibration) 0.007f else maxOf(prb * 0.30f, 0.006f)

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
