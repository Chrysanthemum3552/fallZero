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
 * 운동 #5: 발끝 들기 (Toe Raise / Dorsiflexion) — **정면 촬영** (측면 흔들림 회피, 사용자 요청).
 * 양발 발끝을 들어 올렸다 내리기.
 * 메트릭: 양발 (ankle.y − foot_index.y)/SBU 평균 = 발목 대비 발끝이 올라온 정도. "가장 평평한 발" baseline 대비 신호.
 */
class ToeRaiseEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "발끝 들기"
    override val coachingCueMessage = "발끝을 더 들어주세요."
    override val debugTag = "ToeDebug"

    init {
        // 스무딩 없음 (alpha=1.0) — 발끝 좌표가 충분히 안정적이라 평활 불필요 (사용자 요청).
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 1.0f)
    }

    private var toeDebugCounter = 0
    // 진단용 통계
    private var ratioMin = Float.MAX_VALUE
    private var ratioMax = -Float.MAX_VALUE
    private var signalMin = Float.MAX_VALUE
    private var signalMax = -Float.MAX_VALUE

    // ratio = 양발 (ankle.y − foot_index.y)/sbu 평균 (정면: 발끝이 발목 대비 올라온 정도).
    // baseline = 최근 윈도우(약 4초)의 낮은 percentile = "가장 평평한 발" — 빠른 반복 중에도 평평한 프레임을
    //   충분히 담아 기준이 위로 drift/고정되지 않게 한다 (사용자: 바닥 기준을 상대 갱신).
    private val WINDOW_SIZE = 120
    private val ratioBuffer = FloatArray(WINDOW_SIZE)
    private var bufferIdx = 0
    private var bufferFilled = false

    // PDF §8 — "몸통 반동" 보상 동작 검출용. 측면 자세에서 shoulder.x가 baseline 대비 큰 변동.
    // calibration 중에는 baseline 안 잡음 (발끝 들기 연습으로 약간의 흔들림 발생 가능).
    private var shoulderXBaseline: Float = Float.NaN
    private var swayBaselineFrameCount = 0
    private val SWAY_BASELINE_FRAMES = 30
    private val SWAY_THRESHOLD = 0.25f         // SBU의 25% — 발끝 들 때 자연스러운 상체 보상 동작 허용 (노인 대상 완화 0.10→0.25, CalfRaise와 동일)

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        // 정면 전환(실험): 양발 발끝(foot_index 31/32)이 발목(27/28) 대비 얼마나 올라왔는지 — 두 발 평균.
        //   측면의 '먼 다리 가림 + heel 떨림'을 피하려고 ankle(안정)+toe만 쓰고, 양발 평균으로 안정화.
        val lAnkle = landmarks[LandmarkIndex.LEFT_ANKLE]; val lToe = landmarks[LandmarkIndex.LEFT_FOOT_INDEX]
        val rAnkle = landmarks[LandmarkIndex.RIGHT_ANKLE]; val rToe = landmarks[LandmarkIndex.RIGHT_FOOT_INDEX]
        val lVis = minOf(lAnkle.visibility().orElse(0f), lToe.visibility().orElse(0f))
        val rVis = minOf(rAnkle.visibility().orElse(0f), rToe.visibility().orElse(0f))
        val lLift = (lAnkle.y() - lToe.y()) / sbu   // 발끝 들면 toe.y↓ → 값 증가
        val rLift = (rAnkle.y() - rToe.y()) / sbu
        val ratio = when {
            lVis > 0.3f && rVis > 0.3f -> (lLift + rLift) / 2f
            lVis > 0.3f -> lLift
            rVis > 0.3f -> rLift
            else -> return null
        }

        ratioMin = min(ratioMin, ratio); ratioMax = max(ratioMax, ratio)

        // baseline = 최근 윈도우(약 4초)의 5th percentile = "가장 평평한 발" — 매 프레임 상대 갱신.
        ratioBuffer[bufferIdx] = ratio
        bufferIdx = (bufferIdx + 1) % WINDOW_SIZE
        if (bufferIdx == 0) bufferFilled = true
        val effSize = if (bufferFilled) WINDOW_SIZE else bufferIdx
        val baseline = if (effSize >= 5) {
            val pctIdx = (effSize * 0.05f).toInt().coerceAtLeast(0)
            com.fallzero.app.pose.nthSmallest(ratioBuffer.copyOf(effSize), pctIdx, 0, effSize - 1)
        } else ratio
        val signal = ratio - baseline
        signalMin = min(signalMin, signal); signalMax = max(signalMax, signal)

        toeDebugCounter++
        if (toeDebugCounter % 30 == 0) {
            Log.d("ToeDebug",
                "FRONT lLift=%.4f rLift=%.4f vis(l=%.2f r=%.2f) ratio=%.4f baseline=%.4f signal=%.4f sbu=%.4f".format(
                    lLift, rLift, lVis, rVis, ratio, baseline, signal, sbu))
            Log.d("ToeDebug",
                "STATS ratio[%.4f~%.4f Δ%.4f] signal[%.4f~%.4f Δ%.4f] motThr=%.4f retThr=%.4f".format(
                    ratioMin, ratioMax, ratioMax - ratioMin, signalMin, signalMax, signalMax - signalMin,
                    getMotionThreshold(), getReturnThreshold()))
        }
        return signal
    }

    /**
     * 검출 순서 (PDF §8):
     *   1) 무릎 과도 굽힘 — kneeFlexion > 15° (기존)
     *   2) 몸통 반동 — shoulder.x가 baseline 대비 SBU의 SWAY_THRESHOLD 초과 (신규)
     */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE)
            ?: return null
        val (hip, knee, ankle) = getHipKneeAnkle(landmarks, side)
        val kneeAngle = AngleCalculator.calculateAngle(hip, knee, ankle)
        val kneeFlexion = (180f - kneeAngle).coerceAtLeast(0f)
        if (kneeFlexion > 15f) return "무릎을 펴주세요"

        // 몸통 반동 — calibration 중에는 baseline 신뢰 불가.
        if (isInCalibration) return null
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
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
    // inactivity 임계값
    override val movementThreshold = 0.005f
    // CalfRaise와 동일 — 작은 ROM 운동의 조기 종료 방지 (4초 → 8초)
    override val inactivityTimeoutMs = 8000L
    // PRB 기반 (사용자 요청): 연습(캘리브레이션) 2회로 "최대 발끝 들림"(prb)을 측정 →
    //   본운동에서 그 75% 이상 들면 1회로 인정. 연습 중에는 작은 기본값으로 동작을 잡는다.
    //   prb가 너무 작을 때 대비 하한(floor)도 둠.
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
        shoulderXBaseline = Float.NaN
        swayBaselineFrameCount = 0
    }
}
