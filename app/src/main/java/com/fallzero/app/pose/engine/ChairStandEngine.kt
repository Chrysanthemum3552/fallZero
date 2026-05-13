package com.fallzero.app.pose.engine

import android.util.Log
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.AngleCalculator.Side
import com.fallzero.app.pose.SBUCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

/**
 * 운동 #7: 앉았다 일어서기 (Sit to Stand / Chair Stand)
 * 검사세션(30초 계수)과 훈련세션(횟수 기반) 공용.
 *
 * 메트릭: (ankleY - shoulderMidY) * 100 (정규화 없음 — 거리별 차이는 동적 임계값으로 보상)
 *   서 있을 때: 어깨 높음 → M 큼 (~45~65, 거리에 따라 다름)
 *   앉았을 때: 어깨 낮음 → M 작음 (~30~45, 거리에 따라 다름)
 *
 * 검사 모드: 3초 서있는 동안 측정한 standingBaseline으로 동적 임계값 설정.
 */
class ChairStandEngine(
    targetCount: Int = 10,
    private val examMode: Boolean = false
) : BaseRepEngine(targetCount) {

    override val exerciseName = "앉았다 일어서기"
    override val coachingCueMessage = "완전히 일어나주세요."
    override val debugTag = "ChairDebug"

    init {
        if (examMode) {
            setPRB(1f)
        }
        // 빠른 앉았다-일어서기에 대응: smoother를 반응적으로 — 사용자 명시 권한:
        // logcat 분석상 alpha=0.5에서 빠른 동작 시 smoothed metric이 raw를 따라잡지 못해 retThr 미달 → 카운트 누락.
        // alpha 0.5 → 0.8 (빠른 수렴). threshold/state machine 등 다른 감지 로직 0건 수정.
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.8f)
    }

    // ── 동적 임계값 (검사 모드 전용: ExamFragment.calibrateStanding으로 설정) ──
    private var dynamicMotionThreshold = 39f   // 기본값 (calibrateStanding 호출 전)
    private var dynamicReturnThreshold = 42f
    // 훈련 모드: IDLE 상태(=사용자 서있음)의 max M을 standing baseline으로 추적.
    // 캘리브레이션 여부 무관 — 본 운동 진입 후에도 갱신해서 PRB 이미 있는 운동도 정상 동작 보장.
    private var trainingStandingBaseline = 0f
    // 진단용 프레임 카운터 (사용자 시연 디버그 — 카운트 안 늘어나는 원인 찾기)
    private var diagFrameCount = 0

    /**
     * 서 있는 상태의 M 값을 기반으로 동적 임계값 설정.
     * ExamFragment에서 3초간 서있는 동안 측정한 평균 M을 전달.
     *
     * 예: standingM=52 → motionThreshold=43 (sit), returnThreshold=47 (stand)
     * 예: standingM=48 → motionThreshold=39, returnThreshold=43
     *
     * 원리: 앉으면 M이 서있을 때보다 ~10~15 낮아짐.
     *       motionThreshold = standingM - 9 (확실히 앉음)
     *       returnThreshold = standingM - 5 (확실히 일어섬)
     */
    fun calibrateStanding(standingM: Float) {
        dynamicMotionThreshold = standingM - 9f
        dynamicReturnThreshold = standingM - 5f
        Log.d("ChairDebug", "Calibrated: standingM=%.1f → motionThr=%.1f returnThr=%.1f".format(
            standingM, dynamicMotionThreshold, dynamicReturnThreshold))
    }

    override fun extractMetric(landmarks: List<NormalizedLandmark>): Float? {
        // 발목 visibility 가드 — 발목 둘 다 거의 안 보이면(0.3 미만) MediaPipe 추정 좌표가 비정상.
        // 이 경우 M이 -30 ~ 290 같은 garbage 값 나옴 → 잘못된 state 전이 유발.
        // null 반환 시 BaseRepEngine은 state 변경 안 함 → IDLE 유지.
        val visLAnkle = landmarks[LandmarkIndex.LEFT_ANKLE].visibility().orElse(0f)
        val visRAnkle = landmarks[LandmarkIndex.RIGHT_ANKLE].visibility().orElse(0f)
        if (visLAnkle < 0.3f && visRAnkle < 0.3f) return null

        val shoulderMidY = (landmarks[LandmarkIndex.LEFT_SHOULDER].y() +
                landmarks[LandmarkIndex.RIGHT_SHOULDER].y()) / 2
        val ankleY = maxOf(landmarks[LandmarkIndex.LEFT_ANKLE].y(),
                landmarks[LandmarkIndex.RIGHT_ANKLE].y())
        // 정규화 없이 단순 차이 × 100 (가장 안정적). BaseRepEngine debugTag로 일괄 로깅.
        val m = (ankleY - shoulderMidY) * 100f
        // 훈련 모드: IDLE 상태의 max M = standing baseline. 캘리브레이션 여부 무관하게 트래킹.
        //   - 캘리브레이션 첫 운동: 연습 IDLE에서 baseline 잡힘 → 본 운동 시 dynamic threshold 즉시 적용
        //   - PRB 이미 있는 2번째 이후 운동: 본 운동 시작 직후 IDLE에서 baseline 잡힘 → 즉시 dynamic
        // 비현실적인 outlier(>100) 무시 (mediapipe noise 또는 user 화면 이탈).
        if (!examMode && state == EngineState.IDLE && m in 30f..80f && m > trainingStandingBaseline) {
            trainingStandingBaseline = m
            Log.d("ChairDebug", "TRAIN baseline updated: standingM=%.1f (motThr=%.1f retThr=%.1f, prb=%.1f)".format(
                m, m - 9f, m - 5f, prb))
        }

        // ── 시연 진단 로그 (매 프레임) — 카운트 안 늘어나는 원인 추적 ──
        // adb logcat -s "ChairStandDiag" 로 필터 가능
        diagFrameCount++
        val visLSh = landmarks[LandmarkIndex.LEFT_SHOULDER].visibility().orElse(0f)
        val visRSh = landmarks[LandmarkIndex.RIGHT_SHOULDER].visibility().orElse(0f)
        val visLAn = landmarks[LandmarkIndex.LEFT_ANKLE].visibility().orElse(0f)
        val visRAn = landmarks[LandmarkIndex.RIGHT_ANKLE].visibility().orElse(0f)
        val motThr = getMotionThreshold()
        val retThr = getReturnThreshold()
        // meetsMotion = M ≤ motThr (앉음 감지), meetsReturn = M ≥ retThr (일어섬 감지)
        val meetsMot = m <= motThr
        val meetsRet = m >= retThr
        Log.d("ChairStandDiag",
            "F#%d state=%s inCal=%b cnt=%d M=%.1f motThr=%.1f retThr=%.1f meetsMot=%b meetsRet=%b base=%.1f prb=%.1f reliable=%b visSh(L=%.2f R=%.2f) visAnkle(L=%.2f R=%.2f)".format(
                diagFrameCount, state, isInCalibration, currentCount,
                m, motThr, retThr, meetsMot, meetsRet,
                trainingStandingBaseline, prb, isBaselineReliable(),
                visLSh, visRSh, visLAn, visRAn))
        return m
    }

    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        // CDC 프로토콜: 깊이/높이 피드백 없이 카운트만 측정
        return null
    }

    override val metricIncreasing = false

    /**
     * 카운트 시점을 일어서는 시점(RETURNING → IDLE 확정)으로 강제.
     * metricIncreasing=false 이라 motion threshold 도달 = 앉는 시점인데, 의자 일어서기는
     * "일어선 횟수"로 세는 게 임상 정의(30-Second Chair Stand Test) + 사용자 의도.
     */
    /** ChairStand: 완전 일어선 시점에 카운트 — 임상 정의 (30-Second Chair Stand Test) */
    override val countTiming = CountTiming.ON_FULL_RETURN

    /**
     * ChairStand 임계값 — metricIncreasing=false 의미 변환:
     *   meetsMotion = M ≤ motionThreshold (앉음 감지)
     *   meetsReturn = M ≥ returnThreshold (일어섬 감지)
     * 올바른 hysteresis: motionThreshold < returnThreshold (둘 사이가 노이즈 zone).
     *
     * 훈련 모드 임계값 — baseline-prb gap에 비례한 동적 임계값:
     *  - sit (prb) 위 30% = motionThr (앉음 감지)
     *  - sit (prb) 위 70% = returnThr (일어섬 감지)
     *  - gap이 작은 사용자(perspective 영향)도 적절히 처리되도록 비례 방식 사용.
     *  - 최소 gap 5 요구 (noise 보호).
     */
    private fun isBaselineReliable(): Boolean =
        trainingStandingBaseline > 0f && (trainingStandingBaseline - prb) >= 5f

    override fun getMotionThreshold(): Float = when {
        examMode -> dynamicMotionThreshold
        isInCalibration -> 39f                                    // 캘리브레이션 모드 기본값 (PRB=0 안전 처리)
        isBaselineReliable() -> {                                 // 비례 임계값: sit 위 30%
            val gap = trainingStandingBaseline - prb
            prb + gap * 0.30f
        }
        else -> prb + 9f                                          // fallback: prb(sit) + 9
    }

    override fun getReturnThreshold(): Float = when {
        examMode -> dynamicReturnThreshold
        isInCalibration -> 42f                                    // 캘리브레이션 모드 기본값
        isBaselineReliable() -> {                                 // 비례 임계값: sit 위 70%
            val gap = trainingStandingBaseline - prb
            prb + gap * 0.70f
        }
        else -> prb + 13f                                         // fallback: prb(sit) + 13
    }

    override fun reset() {
        super.reset()
        // 다른 운동 후 재진입 대비: baseline 트래킹 초기화
        trainingStandingBaseline = 0f
        diagFrameCount = 0
        if (!examMode) {
            dynamicMotionThreshold = 39f
            dynamicReturnThreshold = 42f
        }
    }

    /** 사용자 명시 권한: 시각화 전용 read-only 함수. 카운트 로직(extractMetric/detectError/threshold/state machine)
     *  은 절대 건드리지 않음. landmarks에서 raw m을 계산해 progress 0~1로 반환만.
     *  sit/stand 경계는 기존 임계값 그대로 사용 — 새 변수 추가 X. */
    override fun getGuide(landmarks: List<NormalizedLandmark>): com.fallzero.app.ui.overlay.ExerciseGuide? {
        if (isInCalibration) return null
        if (landmarks.size < 33) return null
        val visLAnkle = landmarks[LandmarkIndex.LEFT_ANKLE].visibility().orElse(0f)
        val visRAnkle = landmarks[LandmarkIndex.RIGHT_ANKLE].visibility().orElse(0f)
        if (visLAnkle < 0.3f && visRAnkle < 0.3f) return null
        val shoulderMidY = (landmarks[LandmarkIndex.LEFT_SHOULDER].y() +
                landmarks[LandmarkIndex.RIGHT_SHOULDER].y()) / 2
        val ankleY = maxOf(landmarks[LandmarkIndex.LEFT_ANKLE].y(),
                landmarks[LandmarkIndex.RIGHT_ANKLE].y())
        val m = (ankleY - shoulderMidY) * 100f
        // sit/stand 경계 (시각화 전용 — 카운트 threshold와 별개):
        //   사용자 명시: 살짝 앉아도 progress=0 너무 빨리 도달 → sitFloor 더 낮춤 (더 깊이 앉아야 0).
        //   sitFloor = dynamicMotionThreshold - 4 (검사) / prb - 4 (훈련) — 카운트는 그대로, 시각화만 엄격.
        val (sitFloor, standCeiling) = when {
            examMode -> Pair(dynamicMotionThreshold - 4f, dynamicReturnThreshold)
            trainingStandingBaseline > 0f && (trainingStandingBaseline - prb) >= 5f ->
                Pair(prb - 4f, trainingStandingBaseline)
            else -> Pair(prb - 4f, prb + 13f)
        }
        val gap = standCeiling - sitFloor
        val progress = if (gap > 0f) ((m - sitFloor) / gap).coerceIn(0f, 1f) else 0f
        return com.fallzero.app.ui.overlay.ExerciseGuide.Bar(
            progress = progress,
            vertical = true,
            fillDirection = com.fallzero.app.ui.overlay.ExerciseGuide.FillDirection.UP,
            label = "$exerciseName 진행도",
            justReached = progress >= 1f
        )
    }
}
