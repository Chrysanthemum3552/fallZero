package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 #6: 무릎 살짝 굽히기 (Mini Squat / Knee Bend) — **정면 촬영** (사용자 요청: 측면 흔들림 회피).
 *
 * 정면에서는 무릎 굽힘 각도(2D)가 거의 안 변하므로, 무릎을 굽히면 **몸이 내려가는 것(hip 하강)**으로 감지한다.
 * 메트릭: (hipY - 서있을때 hipY) / SBU × 100  → 직립 ≈ 0, 굽힐수록 증가.
 *   - hip(23/24, 양쪽 평균)만 사용 — 발(29~32)·얼굴(0~10) 같은 불안정 관절 미사용.
 *   - baseline = 최근 90프레임 hipY의 10th percentile (= 가장 높은 위치 = 서있는 자세, outlier robust).
 *   - 임계값은 logcat(KneeBendDebug) 실측으로 튜닝.
 */
class KneeBendEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "무릎 굽히기"
    override val coachingCueMessage = "무릎을 더 굽혀주세요."
    override val debugTag = "KneeBendDebug"

    private val WINDOW = 90
    private val hipYBuffer = FloatArray(WINDOW)
    private var bufferIdx = 0
    private var bufferFilled = false
    private var dbgCounter = 0

    init {
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.5f)
    }

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val lHip = landmarks[LandmarkIndex.LEFT_HIP]
        val rHip = landmarks[LandmarkIndex.RIGHT_HIP]
        if (lHip.visibility().orElse(0f) < 0.2f && rHip.visibility().orElse(0f) < 0.2f) return null
        val hipY = (lHip.y() + rHip.y()) / 2f

        hipYBuffer[bufferIdx] = hipY
        bufferIdx = (bufferIdx + 1) % WINDOW
        if (bufferIdx == 0) bufferFilled = true
        val effSize = if (bufferFilled) WINDOW else bufferIdx
        // 10th percentile = 가장 높은 위치(작은 y) = 서있는 자세 baseline
        val baseline = if (effSize >= 10) {
            val pctIdx = (effSize * 0.10f).toInt().coerceAtLeast(0)
            com.fallzero.app.pose.nthSmallest(hipYBuffer.copyOf(effSize), pctIdx, 0, effSize - 1)
        } else hipY

        // 몸이 내려간 정도 — 서있을 때 0, 굽힐수록 +. /sbu로 거리 독립.
        val metric = (((hipY - baseline) / sbu) * 100f).coerceAtLeast(0f)

        dbgCounter++
        if (dbgCounter % 30 == 0) {
            Log.d("KneeBendDebug", "FRONT hipY=%.4f baseline=%.4f sbu=%.4f metric=%.2f motThr=%.1f retThr=%.1f cal=%b".format(
                hipY, baseline, sbu, metric, getMotionThreshold(), getReturnThreshold(), isInCalibration))
        }
        return metric
    }

    // 정면 전환 — 측면 전용 "무릎 전방 돌출/과도 굽힘" 경고 제거 (오작동 방지).
    override fun detectError(landmarks: List<NormalizedLandmark>): String? = null

    override val metricIncreasing = true

    // hip 하강 metric — 살짝 굽히면 대략 15~30 (logcat 실측으로 튜닝 예정). 초기 추정값.
    private val MAX_PRB = 35f
    override fun getMotionThreshold() = if (isInCalibration) 12f
        else maxOf(prb.coerceAtMost(MAX_PRB) * 0.60f, 9f)
    override fun getReturnThreshold() = if (isInCalibration) 5f else 6f

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
        for (i in hipYBuffer.indices) hipYBuffer[i] = 0f
        bufferIdx = 0
        bufferFilled = false
        dbgCounter = 0
    }
}
