package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 #1: 대퇴사두근 강화 (Knee Extension) — **정면 촬영** (측면 먼 다리 가림·떨림 회피, 사용자 확정).
 *
 * 의자에 앉아 카메라를 정면으로 보고 한쪽 다리를 앞으로 쭉 폈다 내린다.
 * 정면에서는 무릎 각도(2D)가 카메라 쪽으로 뻗을수록 찌그러져(foreshortening) 부정확하므로,
 *   대신 **발목이 무릎 높이로 올라오는 정도** sig = (kneeY − ankleY)/SBU 를 본다.
 *   굽힘(발목이 무릎보다 한참 아래) ≈ −1.1~−1.25, 폄(발목≈무릎) ≈ −0.55. baseline = 다리별 윈도우 percentile(바닥).
 *
 * 양측 lockedSide 처리 (정면이라 두 다리 모두 보임 — #2 HipAbduction과 동일 패턴, 사용자 요청):
 *  - 첫 카운트에 lockedSide = 그 rep에서 더 편(signal 큰) 다리로 잠금
 *  - 이후 lockedSide 다리만 카운트, 반대 다리를 펴면 "반대쪽 다리로 해주세요" 경고
 *  - 두 번째 섹션은 onSideSwitch()로 lockedSide flip (ViewModel.startRightSide가 reset() 직후 호출)
 */
class KneeExtensionEngine(targetCount: Int = 10) : BaseRepEngine(targetCount) {

    override val exerciseName = "대퇴사두근 강화"
    override val coachingCueMessage = "다리를 더 펴주세요."
    override val debugTag = "KneeExtDebug"

    init {
        // 막대기 떨림 억제 EMA 스무딩 (사용자 보고). alpha=0.5 — 느린 운동이라 지연 영향 작음.
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.5f)
    }

    private var dbgCounter = 0
    // 다리별 발-바닥 baseline + signal. sig 스무딩(jitter 억제) + '휴식 평균' floor 추적으로
    //   rest jitter가 rectify(max(0,·))되어 막대기를 스스로 채우던 문제(사용자 보고) 차단.
    private val floorL = FloorTracker()
    private val floorR = FloorTracker()

    // 양측 lockedSide — null이면 미잠금(둘 중 큰 쪽 사용), 잠금 후엔 그 다리만 카운트.
    private var lockedSide: Side? = null
    // 미잠금 상태에서 peak(motThr 도달) 시 우세 다리를 기록 — 카운트가 복귀 시점이라 그때는 양다리 signal ~0이므로 peak에서 잡아둠.
    private var peakLockCandidate: Side? = null
    // 매 frame 다리별 signal 캐시 (onFirstCount/detectError 재사용)
    private var lastSignalL = 0f
    private var lastSignalR = 0f
    // wrong-leg 1 cycle 추적 (HipAbduction과 동일)
    private var wrongLegPeak = 0f

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        val sbu = SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return null
        val lKnee = landmarks[LandmarkIndex.LEFT_KNEE]; val lAnkle = landmarks[LandmarkIndex.LEFT_ANKLE]
        val rKnee = landmarks[LandmarkIndex.RIGHT_KNEE]; val rAnkle = landmarks[LandmarkIndex.RIGHT_ANKLE]
        val lVis = minOf(lKnee.visibility().orElse(0f), lAnkle.visibility().orElse(0f))
        val rVis = minOf(rKnee.visibility().orElse(0f), rAnkle.visibility().orElse(0f))
        val sigL = (lKnee.y() - lAnkle.y()) / sbu   // 굽힘 ≈ −1.2, 폄 ≈ −0.55
        val sigR = (rKnee.y() - rAnkle.y()) / sbu

        // 다리별 signal (sig 스무딩 + 휴식평균 floor) — 가시성 좋은 프레임만. rest jitter 정류 문제 차단.
        val signalL = if (lVis > 0.5f) floorL.signal(sigL) else 0f
        val signalR = if (rVis > 0.5f) floorR.signal(sigR) else 0f
        lastSignalL = signalL; lastSignalR = signalR

        // 미잠금 + 우세 다리가 motThr 도달(=폄 peak) → lock 후보 기록 (카운트 시점엔 양다리 ~0이라 그때 못 정함).
        if (lockedSide == null && maxOf(signalL, signalR) >= getMotionThreshold()) {
            peakLockCandidate = if (signalL >= signalR) Side.LEFT else Side.RIGHT
        }

        // 진단 로그 — 원시 sig(jitter 크기)·signal·가시성. 가만히 있을 때 막대기 차오름 + 원인(jitter vs 가림) 확인.
        dbgCounter++
        if (dbgCounter % 10 == 0) {
            Log.d("KneeExtDebug", "lock=%s L[sig=%.3f s=%.3f v=%.2f] R[sig=%.3f s=%.3f v=%.2f] mot=%.3f".format(
                lockedSide, sigL, signalL, lVis, sigR, signalR, rVis, getMotionThreshold()))
        }

        return when (lockedSide) {
            Side.LEFT -> if (lVis > 0.5f) signalL else return null
            Side.RIGHT -> if (rVis > 0.5f) signalR else return null
            null -> if (lVis > 0.5f || rVis > 0.5f) maxOf(signalL, signalR) else return null
        }
    }

    /** 첫 카운트 시점에 lockedSide 결정 = peak에서 더 편 다리(peakLockCandidate). */
    override fun onFirstCount(landmarks: List<NormalizedLandmark>) {
        if (lockedSide == null) {
            lockedSide = peakLockCandidate ?: if (lastSignalL >= lastSignalR) Side.LEFT else Side.RIGHT
        }
    }

    /** 두 번째 섹션 시작 시 lockedSide flip (ViewModel.startRightSide가 reset() 직후 호출). */
    override fun onSideSwitch() {
        lockedSide = when (lockedSide) {
            Side.LEFT -> Side.RIGHT
            Side.RIGHT -> Side.LEFT
            null -> null
        }
        wrongLegPeak = 0f
    }

    /** wrong-leg 경고: 반대쪽 다리가 1 cycle(motThr 도달 후 retThr 복귀) 완료 + 잠긴 다리는 거의 안 움직임. */
    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        val lock = lockedSide ?: return null
        val locked = if (lock == Side.LEFT) lastSignalL else lastSignalR
        val other = if (lock == Side.LEFT) lastSignalR else lastSignalL
        val motThr = getMotionThreshold(); val retThr = getReturnThreshold()
        if (other >= motThr) wrongLegPeak = maxOf(wrongLegPeak, other)
        if (wrongLegPeak >= motThr && other < retThr && locked < retThr) {
            wrongLegPeak = 0f
            return "반대쪽 다리로 해주세요."
        }
        return null
    }

    override val metricIncreasing = true
    // signal 0(굽힘)~0.35(편안한 폄, 실측). 천천히 펼 때 frame 간 변화량 미달로 조기 종료되지 않게 작은 값.
    override val movementThreshold = 0.005f
    override val inactivityTimeoutMs = 8000L
    // logcat 실측: 편안한 폄 signal ≈ 0.15~0.35, 휴식 노이즈 ≈ 0.016. 편안한 폄으로 세지도록 완화 (사용자 보고).
    override fun getMotionThreshold() = if (isInCalibration) 0.13f else maxOf(prb * 0.40f, 0.12f)
    override fun getReturnThreshold() = if (isInCalibration) 0.06f else maxOf(prb * 0.20f, 0.05f)

    /** 막대기 시각화(읽기 전용) — lastMetric(=잠긴 다리 signal)을 progress로 변환. 좌표 판정 로직과 무관. */
    override fun getGuide(landmarks: List<NormalizedLandmark>): com.fallzero.app.ui.overlay.ExerciseGuide? {
        if (isInCalibration) return null
        // 막대기 시각 범위는 카운트 임계값과 분리 — 휴식 노이즈(~0.016) 바로 위(0.04)부터 차오르게(dead zone 축소, 사용자 보고).
        val visFloor = 0.04f
        val visCeil = maxOf(getMotionThreshold(), visFloor + 0.02f)
        val progress = ((lastMetric - visFloor) / (visCeil - visFloor)).coerceIn(0f, 1f)
        return com.fallzero.app.ui.overlay.ExerciseGuide.Bar(
            progress = progress, vertical = true,
            fillDirection = com.fallzero.app.ui.overlay.ExerciseGuide.FillDirection.UP,
            label = "$exerciseName 진행도", justReached = progress >= 1f
        )
    }

    override fun reset() {
        super.reset()
        dbgCounter = 0
        floorL.reset(); floorR.reset()
        lastSignalL = 0f; lastSignalR = 0f
        wrongLegPeak = 0f
        peakLockCandidate = null
        // lockedSide는 여기서 건드리지 않음 — startRightSide가 reset() 후 onSideSwitch()로 flip하므로(=HipAbduction 패턴).
        //   새 운동은 새 엔진 인스턴스라 lockedSide 기본 null.
    }

    /** 한쪽 다리의 발-바닥 baseline + signal 계산.
     *  rest jitter(원시 sig가 휴식 중 크게 출렁임)가 max(0,·) 정류되어 막대기를 스스로 채우던 문제(사용자 보고) 차단:
     *   1) sig를 EMA로 스무딩 → jitter 진폭 축소.
     *   2) floor = 스무딩 sig의 EMA를 '휴식 중에만' 추적(폄=REST_BAND 초과 상승 시 동결)
     *      → 휴식 평균에 수렴해 signal = (sigEma − floor) ≈ 0 (정류 bias 제거). 폄은 동결된 floor 대비 그대로 측정.
     *   3) 하향 글리치는 sigEma가 floor 아래라 signal=0. */
    private class FloorTracker {
        private var sigEma = Float.NaN
        private var floor = Float.NaN
        fun signal(sigRaw: Float): Float {
            sigEma = if (sigEma.isNaN()) sigRaw else sigEma + (sigRaw - sigEma) * SIG_ALPHA
            if (floor.isNaN()) floor = sigEma
            val rise = sigEma - floor
            if (rise < REST_BAND) floor += (sigEma - floor) * FLOOR_ALPHA   // 휴식/미세움직임 — 평균 추적 (폄이면 동결)
            return (sigEma - floor).coerceAtLeast(0f)
        }
        fun reset() { sigEma = Float.NaN; floor = Float.NaN }
        companion object {
            private const val SIG_ALPHA = 0.3f     // sig 스무딩 (jitter 억제). 낮을수록 매끈하나 폄 반응 지연.
            private const val FLOOR_ALPHA = 0.08f  // floor가 휴식 평균을 따라가는 속도 (정류 bias/creep 제거).
            private const val REST_BAND = 0.12f    // 이 이내 상승은 휴식(floor 추적), 초과는 폄(floor 동결).
        }
    }
}
