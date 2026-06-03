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
        // 훈련 모드 standing baseline 트래킹 — 사용자 명시 권한 (의자 sit이 detect 안 되는 버그 수정):
        //   PRB(앉은 자세 캘리브레이션 값) 의존 완전 제거. exam mode와 동일한 standing-baseline 공식 사용.
        //   sit 값이 baseline으로 잘못 잡히지 않도록 STANDING_MIN(42) 이상의 M만 baseline 후보로 인정.
        //   이 임계값 미만의 M은 의자 sit이거나 측정 노이즈로 간주.
        // 연습(캘리브레이션) 구간에서도 baseline을 잡도록 isInCalibration 제외 조건 제거 —
        // 검사 세션과 동일하게 standing baseline 기반 동적 임계값을 연습부터 적용(사용자 명시: req4 의자 운동만).
        if (!examMode && m in STANDING_MIN..80f && m > trainingStandingBaseline) {
            trainingStandingBaseline = m
            Log.d("ChairDebug", "TRAIN baseline updated: standingM=%.1f state=%s motThr=%.1f retThr=%.1f".format(
                m, state, m - 9f, m - 5f))
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
            "F#%d state=%s inCal=%b cnt=%d M=%.1f motThr=%.1f retThr=%.1f meetsMot=%b meetsRet=%b base=%.1f visSh(L=%.2f R=%.2f) visAnkle(L=%.2f R=%.2f)".format(
                diagFrameCount, state, isInCalibration, currentCount,
                m, motThr, retThr, meetsMot, meetsRet,
                trainingStandingBaseline,
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
     * 훈련 모드 임계값 — exam mode와 동일 식: standingBaseline 기준 (PRB 의존 제거).
     *   사용자 명시 권한 — PRB 기반 공식이 "캘리브레이션 sit 자세"보다 얕은 의자 sit을 detect 못 함.
     *   baseline 잡힐 때까지(첫 stand-up 1초 내외) 합리적 기본값 사용.
     *   기본값(motion=41, return=45)은 exam mode의 평균 사용자(standingM≈50)와 동일.
     */
    override fun getMotionThreshold(): Float = when {
        examMode -> dynamicMotionThreshold
        trainingStandingBaseline > 0f -> trainingStandingBaseline - 9f  // 연습·본운동 공통(검사와 동일 식) — baseline 잡히면 즉시 적용
        isInCalibration -> 39f                                          // baseline 잡히기 전 연습 기본값
        else -> DEFAULT_MOTION_THR                                      // baseline 잡히기 전 안전값
    }

    override fun getReturnThreshold(): Float = when {
        examMode -> dynamicReturnThreshold
        trainingStandingBaseline > 0f -> trainingStandingBaseline - 5f
        isInCalibration -> 42f
        else -> DEFAULT_RETURN_THR
    }

    companion object {
        /** standing baseline 후보로 인정할 M 하한 — 이 미만은 sit 또는 노이즈로 간주.
         *  사용자 평균 standing M(45~65)보다 약간 낮게 잡아 보수적 standing 후보만 채택. */
        private const val STANDING_MIN = 42f
        /** baseline 잡히기 전 기본 motion threshold (exam mode standingM=50 → 50-9=41 가정). */
        private const val DEFAULT_MOTION_THR = 41f
        /** baseline 잡히기 전 기본 return threshold (exam mode standingM=50 → 50-5=45 가정). */
        private const val DEFAULT_RETURN_THR = 45f
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
        //   훈련 모드도 standing baseline 기반으로 통일 (PRB 의존 제거 — getMotionThreshold 변경과 일치).
        //   sitFloor = motionThr - 4 (살짝 앉아도 progress=0 빨리 안 도달하도록 더 깊이).
        val motThr = getMotionThreshold()
        val retThr = getReturnThreshold()
        val sitFloor = motThr - 4f
        val standCeiling = retThr
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
