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
     * 코칭 큐 발화 시 표시·발음할 메시지. null이면 코칭 큐 비활성화.
     * 사용자가 motion threshold에 도달하지 못하고 다시 시작점으로 돌아온 경우(=시도 실패)에
     * 한 번 발화. 즉 "올라가는 중"이 아니라 "올렸다가 부족하게 내린 후"에만 발화.
     */
    val coachingCueMessage: String? get() = null

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

    /**
     * 양측 운동 좌→우 전환 시 호출. 정면 양측 운동(#2 HipAbduction)은 lockedSide flip 등으로 사용.
     * 측면 운동(#1, #3)은 사용자가 카메라 자세 바꿈으로 처리되므로 default no-op.
     */
    fun onSideSwitch() {}

    /**
     * inactivity 감지용 임계값 — ExerciseViewModel이 NO_MOTION_TIMEOUT(4초) 안에 metric 변화량이 이 값 미만이면 자동 종료.
     * 각도 기반 운동(0-180°)은 default 0.5°면 충분. 비율/거리 기반 운동(예: ToeRaise 0-0.03)은 훨씬 작은 값 필요.
     */
    val movementThreshold: Float get() = 0.5f
}

data class FrameResult(
    val count: Int,
    val isCountIncremented: Boolean,
    val hasError: Boolean,
    val errorMessage: String?,
    val isCoachingCue: Boolean = false,        // 시도 실패 시(부족하게 들었다 내림) 한 frame에만 true (이벤트)
    val coachingCueMessage: String? = null,    // isCoachingCue=true일 때 함께 전달되는 운동별 안내 멘트
    val currentMetric: Float = 0f,             // 현재 측정값 (디버깅·PRB갱신용)
    val state: EngineState,
    val calibrationRepCompleted: Boolean = false,  // 연습 모드에서 1회 사이클 완료 (1회/2회 카운트 음성용)
    val calibrationReps: Int = 0                   // 현재까지 완료한 연습 횟수 (0/1/2)
)

/**
 * 운동 사이클 상태.
 *  IDLE       — 시작점(움직임 없음 또는 partial threshold 미만)
 *  ATTEMPTING — partial threshold는 넘었지만 motion threshold 미달 (시도 중)
 *  IN_MOTION  — motion threshold 도달 = 카운트 진행됨, 내려오기 대기
 *  RETURNING  — 내려오는 중 (return threshold 이하 도달)
 *  CALIBRATING — 캘리브레이션 모드 표시용 (내부 상태와는 별개)
 */
enum class EngineState { IDLE, ATTEMPTING, IN_MOTION, RETURNING, CALIBRATING }

/**
 * 카운트가 발생하는 시점.
 *  ON_MOTION_START   — IDLE/ATTEMPTING → IN_MOTION 시점 (들 때 +1, HipAbduction 등)
 *  ON_DESCENT_START  — IN_MOTION → RETURNING 시점 (내려가기 시작할 때 +1, default)
 *  ON_FULL_RETURN    — RETURNING → IDLE 확정 시점 (완전 복귀, ChairStand 임상 정의)
 */
enum class CountTiming { ON_MOTION_START, ON_DESCENT_START, ON_FULL_RETURN }
