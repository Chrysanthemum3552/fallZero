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

    init {
        if (examMode) {
            setPRB(1f)
        }
        // 빠른 앉았다-일어서기에 대응: smoother를 반응적으로
        smoother = com.fallzero.app.pose.MetricSmoother(alpha = 0.5f)
    }

    private var debugFrameCount = 0

    // ── 동적 임계값 (검사 모드에서 standingBaseline 기반으로 설정) ──
    private var dynamicMotionThreshold = 39f   // 기본값 (calibrateStanding 호출 전)
    private var dynamicReturnThreshold = 42f

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
        // 정규화 없이 단순 차이 × 100 (가장 안정적)
        val metric = (ankleY - shoulderMidY) * 100f

        debugFrameCount++
        if (debugFrameCount % 15 == 0) {
            Log.d("ChairDebug", "M=%.1f shY=%.3f ankY=%.3f state=$state motThr=%.1f retThr=%.1f count=$currentCount".format(
                metric, shoulderMidY, ankleY, getMotionThreshold(), getReturnThreshold()))
        }
        return metric
    }

    override fun detectError(landmarks: List<NormalizedLandmark>): String? {
        // CDC 프로토콜: 깊이/높이 피드백 없이 카운트만 측정
        return null
    }

    override val metricIncreasing = false

    override fun getMotionThreshold(): Float = when {
        examMode -> dynamicMotionThreshold
        isInCalibration -> dynamicMotionThreshold
        else -> 0.80f * prb + 20f
    }

    override fun getReturnThreshold(): Float = dynamicReturnThreshold
}
