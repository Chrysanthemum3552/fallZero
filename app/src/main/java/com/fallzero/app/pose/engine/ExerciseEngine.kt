package com.fallzero.app.pose.engine

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 운동 엔진 공통 인터페이스
 * 이승종 담당 - 모든 운동 종목이 구현
 */
interface ExerciseEngine {
    val exerciseName: String
    val currentCount: Int
    val targetCount: Int

    /** 캘리브레이션 진행 중 여부 (첫 훈련세션 첫 2회) */
    val isInCalibration: Boolean

    /** 캘리브레이션 중 측정된 최대 PRB 값 */
    val measuredCalibrationPRB: Float

    /**
     * PRB 설정
     * @param prbValue 0f = 캘리브레이션 미완료 → 자동으로 캘리브레이션 모드 진입
     */
    fun setPRB(prbValue: Float)

    /** 매 프레임 호출 - 랜드마크 좌표 처리 */
    fun processLandmarks(landmarks: List<NormalizedLandmark>): FrameResult

    /** 상태 초기화 */
    fun reset()

    /** 디버그용: 수동으로 카운트를 1 증가시킴 */
    fun debugForceCount() {}
}

data class FrameResult(
    val count: Int,
    val isCountIncremented: Boolean,
    val hasError: Boolean,
    val errorMessage: String?,
    val isCoachingCue: Boolean = false,   // "조금 더 해볼까요?" 구간
    val currentMetric: Float = 0f,        // 현재 측정값 (디버깅·PRB갱신용)
    val state: EngineState
)

enum class EngineState { IDLE, IN_MOTION, RETURNING, CALIBRATING }
