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
        // 빠른 앉았다-일어서기에 대응: smoother를 반응적으로
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.5f)
    }

    // ── 동적 임계값 (검사 모드: ExamFragment.calibrateStanding으로 설정 / 훈련 모드: 연습 IDLE M 캡처로 설정) ──
    private var dynamicMotionThreshold = 39f   // 기본값 (calibrateStanding 호출 전)
    private var dynamicReturnThreshold = 42f
    // 훈련 모드 연습 중 서있을 때의 max M을 추적 — 연습 종료 시 dynamic thresholds로 변환
    private var trainingStandingBaseline = 0f
    // 연습 종료 후 1회만 dynamic threshold 확정 처리하기 위한 flag
    private var trainingThresholdsFinalized = false

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
        val shoulderMidY = (landmarks[LandmarkIndex.LEFT_SHOULDER].y() +
                landmarks[LandmarkIndex.RIGHT_SHOULDER].y()) / 2
        val ankleY = maxOf(landmarks[LandmarkIndex.LEFT_ANKLE].y(),
                landmarks[LandmarkIndex.RIGHT_ANKLE].y())
        // 정규화 없이 단순 차이 × 100 (가장 안정적). BaseRepEngine debugTag로 일괄 로깅.
        val m = (ankleY - shoulderMidY) * 100f
        // 훈련 모드 연습(calibration) 중 IDLE 상태의 max M = standing baseline 트래킹.
        // 연습 중엔 default 임계값(39/42) 사용, 연습 종료 후 isBaselineReliable() 체크로 dynamic 적용.
        // 비현실적인 outlier(>100) 무시 (mediapipe noise 또는 user 화면 이탈).
        if (!examMode && isInCalibration && state == EngineState.IDLE && m in 30f..80f && m > trainingStandingBaseline) {
            trainingStandingBaseline = m
            Log.d("ChairDebug", "TRAIN baseline tracking: standingM=%.1f (will be used after calib if ≥ prb+15)".format(m))
        }
        // 연습 종료 직후(처음 isInCalibration=false 진입) 1회만 dynamic thresholds 확정
        if (!examMode && !isInCalibration && !trainingThresholdsFinalized) {
            trainingThresholdsFinalized = true
            if (trainingStandingBaseline > 0f && (trainingStandingBaseline - prb) >= 15f) {
                dynamicMotionThreshold = trainingStandingBaseline - 9f
                dynamicReturnThreshold = trainingStandingBaseline - 5f
                Log.d("ChairDebug", "TRAIN calib complete: baseline=%.1f prb=%.1f → motThr=%.1f retThr=%.1f".format(
                    trainingStandingBaseline, prb, dynamicMotionThreshold, dynamicReturnThreshold))
            } else {
                Log.d("ChairDebug", "TRAIN calib complete: baseline unreliable (got %.1f, prb=%.1f) → fallback prb+9/+13".format(
                    trainingStandingBaseline, prb))
                // dynamicMotion/Return 기본값(39/42) 그대로 — isBaselineReliable=false → getMotion/Return이 fallback 분기 선택
            }
        }
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
     * 훈련 모드 임계값 우선순위:
     *  1. 신뢰할 수 있는 baseline (prb + 15 이상 차이): dynamic 사용
     *  2. fallback: prb 기반 추정 — typical sit-stand gap 18 가정
     */
    private fun isBaselineReliable(): Boolean =
        trainingStandingBaseline > 0f && (trainingStandingBaseline - prb) >= 15f

    override fun getMotionThreshold(): Float = when {
        examMode -> dynamicMotionThreshold
        isInCalibration -> dynamicMotionThreshold
        isBaselineReliable() -> dynamicMotionThreshold
        else -> prb + 9f                  // fallback: prb(sit) + 9 → motThr ≈ 41-44
    }

    override fun getReturnThreshold(): Float = when {
        examMode -> dynamicReturnThreshold
        isInCalibration -> dynamicReturnThreshold
        isBaselineReliable() -> dynamicReturnThreshold
        else -> prb + 13f                  // fallback: prb(sit) + 13 → retThr ≈ 45-48
    }

    override fun reset() {
        super.reset()
        // 다른 운동 후 재진입 대비: baseline 트래킹/finalize flag 초기화
        trainingStandingBaseline = 0f
        trainingThresholdsFinalized = false
        if (!examMode) {
            dynamicMotionThreshold = 39f
            dynamicReturnThreshold = 42f
        }
    }
}
