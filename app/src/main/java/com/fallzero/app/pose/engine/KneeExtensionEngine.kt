package com.fallzero.app.pose.engine

import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 #1: 대퇴사두근 강화 (Knee Extension)
 * 의자에 앉아 한쪽 다리를 앞으로 쭉 뻗어 올렸다 내리기. 측면 촬영.
 *
 * 사용자 명시: 측면 운동은 좌우 구별 논리 제거 — 좌/우 어느 다리든 펴면 카운트.
 * 사용자 신뢰 기반 (Fragment의 handleSideSwitch가 안내만 함).
 *
 * 자세 오류 검출 (PDF §8):
 *   "골반 들림 / 허벅지 과도 들림" — 두 보상 동작 모두 hip.y가 baseline(앉은 자세)에서
 *   위로 올라가는 현상으로 나타나므로 하나의 hip-rise 게이트로 통합 검출.
 *   "상체 뒤 젖힘"은 측면 자세에서 shoulder.x 차이로만 잡을 수 있는데, 좌측/우측 카메라
 *   방향 따라 부호가 반대라 false positive가 잦아 보류.
 */
class KneeExtensionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "대퇴사두근 강화"
    override val coachingCueMessage = "다리를 더 펴주세요."

    // hip.y baseline — 운동 시작 직후 첫 BASELINE_FRAMES 동안의 EMA로 안정화.
    // 그 후 현재 hip.y가 baseline 대비 SBU의 HIP_RISE_THRESHOLD 이상 위로 올라가면 경고.
    private var hipYBaseline: Float = Float.NaN
    private var baselineFrameCount = 0
    private val BASELINE_FRAMES = 30           // ~1초 (30fps 기준)
    private val HIP_RISE_THRESHOLD = 0.05f     // SBU의 5%

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val (lHip, lKnee, lAnkle) = getHipKneeAnkle(landmarks, Side.LEFT)
        val (rHip, rKnee, rAnkle) = getHipKneeAnkle(landmarks, Side.RIGHT)
        val lKneeVis = lKnee.visibility().orElse(0f)
        val rKneeVis = rKnee.visibility().orElse(0f)
        if (lKneeVis < 0.2f && rKneeVis < 0.2f) return null

        val leftAngle = AngleCalculator.calculateAngle(lHip, lKnee, lAnkle)
        val rightAngle = AngleCalculator.calculateAngle(rHip, rKnee, rAnkle)
        // 어느 쪽이든 더 펴진 쪽을 측정값으로 사용
        return maxOf(leftAngle, rightAngle)
    }

    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val side = AngleCalculator.pickVisibleSide(landmarks, LandmarkIndex.LEFT_HIP, LandmarkIndex.RIGHT_HIP)
            ?: return null
        val hipIdx = if (side == Side.LEFT) LandmarkIndex.LEFT_HIP else LandmarkIndex.RIGHT_HIP
        val hipY = landmarks[hipIdx].y()

        // baseline 안정화 — 첫 30프레임 동안만 갱신(이후 운동 동작에 끌려가지 않게 고정).
        if (baselineFrameCount < BASELINE_FRAMES) {
            hipYBaseline = if (hipYBaseline.isNaN()) hipY else hipYBaseline * 0.7f + hipY * 0.3f
            baselineFrameCount++
            return null
        }

        // image y는 위로 갈수록 작아짐 → baseline - 현재 > 0 = 골반이 위로 올라감.
        val riseRatio = (hipYBaseline - hipY) / sbu
        return if (riseRatio > HIP_RISE_THRESHOLD) "허벅지를 너무 들지 마세요" else null
    }

    override val metricIncreasing = true
    override fun getMotionThreshold() = if (isInCalibration) 130f else maxOf(prb * 0.85f, 130f)
    override fun getReturnThreshold() = if (isInCalibration) 100f else 110f

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

    override fun reset() {
        super.reset()
        hipYBaseline = Float.NaN
        baselineFrameCount = 0
    }
}
