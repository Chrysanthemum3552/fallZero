package com.fallzero.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.algorithm.PRBManager
import com.fallzero.app.data.algorithm.QualityScorer
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.data.repository.PRBRepository
import com.fallzero.app.data.repository.SessionRepository
import com.fallzero.app.pose.engine.EngineState
import com.fallzero.app.pose.engine.ExerciseEngine
import com.fallzero.app.pose.engine.FrameResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ExerciseViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
    private val userId get() = prefs.getInt("user_id", 0)

    private val db = FallZeroDatabase.getInstance(application)
    private val sessionRepository = SessionRepository(db.sessionDao())
    private val prbRepository = PRBRepository(db.prbDao())

    private val _uiState = MutableStateFlow<ExerciseUiState>(ExerciseUiState.Idle)
    val uiState: StateFlow<ExerciseUiState> = _uiState

    private var currentEngine: ExerciseEngine? = null
    private var currentExerciseId: Int = 0
    private var currentSetLevel: Int = 1
    private var currentSessionId: Long = -1
    private var errorCount = 0
    private var calibrationPrbSaved = false
    private var isCompleted = false
    private var currentPrb = 0f

    // 양측 운동 처리
    private var isBilateral = false
    private enum class SidePhase { LEFT, INTERSIDE_REST, RIGHT, DONE }
    private var sidePhase = SidePhase.LEFT
    private var leftCompletedCount = 0
    private var rightCompletedCount = 0
    private var perSideTarget = 10

    // 정적(움직임 없음) 종료 감지: 4초 이상 metric 변화 없음
    private var lastMovementMs = 0L
    private var lastMetricValue = 0f
    private var lastFrameMs = 0L            // 마지막 프레임 처리 시각 (사용자 카메라 이탈 감지용)
    private val NO_MOTION_TIMEOUT_MS = 4000L
    private val FRAME_GAP_THRESHOLD_MS = 1000L  // 프레임이 1초 이상 안 들어오면 = 카메라 이탈

    // 초기화 race 방지: launch coroutine에서 setPRB 완료 전까지 frame 처리 차단
    @Volatile private var isReady = false

    // 안내 화면 가드: Fragment의 카운트다운 + "시작!" 음성 종료 전까지 frame 처리 차단.
    // 사용자는 안내 멘트와 카운트다운 동안 자세를 잡을 시간을 확보 — 잘못된 동작이 측정되지 않음.
    @Volatile private var measurementStarted = false

    // 다차원 품질 점수용 데이터 수집.
    // currentRepPeak: NaN sentinel로 시작 → 첫 capture부터 정확히 추적 (ChairStand decreasing 대응)
    private val repPeakMetrics = mutableListOf<Float>()
    private var currentRepPeak: Float = Float.NaN
    private var lastRepCount = 0

    fun initExercise(engine: ExerciseEngine, exerciseId: Int, setLevel: Int, existingSessionId: Long = -1L) {
        // 초기화 race 방지: setPRB 호출 전까지 frame 처리 차단
        isReady = false
        // 안내 화면 끝(=Fragment의 startMeasurement 호출)까지 frame 차단
        measurementStarted = false
        isAwaitingFinalCount = false
        currentEngine = engine
        currentExerciseId = exerciseId
        currentSetLevel = setLevel
        errorCount = 0
        calibrationPrbSaved = false
        isCompleted = false
        repPeakMetrics.clear()
        currentRepPeak = Float.NaN
        lastRepCount = 0
        lastMovementMs = System.currentTimeMillis()
        lastMetricValue = 0f
        lastFrameMs = 0L

        isBilateral = SessionFlow.isBilateral(exerciseId)
        sidePhase = if (isBilateral) SidePhase.LEFT else SidePhase.DONE
        leftCompletedCount = 0
        rightCompletedCount = 0
        perSideTarget = engine.targetCount

        // 즉시 PRB를 0으로 설정 (sync) → engine이 정상 calibration 모드 진입
        // 이후 launch에서 DB 로드값으로 다시 setPRB
        engine.setPRB(0f)

        viewModelScope.launch {
            currentSessionId = if (existingSessionId > 0L) existingSessionId
                               else sessionRepository.startSession(userId)

            val savedPrb = prbRepository.getLatestPRB(userId, exerciseId)
            currentPrb = savedPrb?.prbValue ?: 0f
            engine.setPRB(currentPrb)
            isReady = true
            _uiState.value = ExerciseUiState.Ready(
                engine.exerciseName,
                engine.targetCount,
                bilateralSide = if (isBilateral) "왼쪽" else null
            )
        }
    }

    fun processLandmarks(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>) {
        // 초기화 미완료, 안내 화면 진행 중, 또는 양측 휴식 중에는 엔진 처리 중지
        if (!isReady || !measurementStarted || sidePhase == SidePhase.INTERSIDE_REST) return
        val result = currentEngine?.processLandmarks(landmarks) ?: return
        onFrameResult(result)
    }

    /**
     * Fragment의 안내 화면 종료(="시작!" 음성 후 1.5초) 시점에 호출 — 측정 시작.
     * 안내 동안 누적된 정적 타이머를 리셋하여 잘못된 inactivity timeout 방지.
     */
    fun startMeasurement() {
        lastMovementMs = System.currentTimeMillis()
        lastMetricValue = 0f
        lastFrameMs = 0L
        measurementStarted = true
    }

    /**
     * 사용자가 카메라 시야에서 이탈했을 때 Fragment에서 호출.
     * frame 처리 차단 + inactivity timer 무효화 (이탈 시간 동안 자동 종료 방지).
     */
    fun pauseForUserAway() {
        measurementStarted = false
    }

    /**
     * 사용자가 다시 카메라 앞에 돌아왔을 때 Fragment의 1.5초 buffer 후 호출.
     * 측정 재개 + inactivity timer 리셋 (이탈 직전 lastMovementMs로 인한 false trigger 방지).
     */
    fun resumeFromUserAway() {
        lastMovementMs = System.currentTimeMillis()
        lastMetricValue = 0f
        lastFrameMs = 0L
        measurementStarted = true
    }

    /**
     * 사용자가 카메라 시야에서 사라졌을 때 호출.
     * 잘못된 inactivity timeout 방지를 위해 움직임 타이머를 리셋.
     */
    fun onBodyLost() {
        // 다음 frame이 들어왔을 때 lastFrameMs가 오래된 것을 보고 갭으로 처리
        // 별도 처리는 불필요 — onFrameResult에서 갭 감지하여 lastMovementMs 갱신
    }

    fun onFrameResult(result: FrameResult) {
        val engine = currentEngine ?: return
        // errorCount는 rep 단위 카운팅 — 카운트 발생 frame에서 errorMessage가 있을 때만 +1.
        // (errorMessage는 매 frame 전달되지만, 이는 transient 피드백용. errorCount는 사이클당 1회.)
        if (result.isCountIncremented && result.errorMessage != null) errorCount++

        val now = System.currentTimeMillis()

        // 프레임 갭 감지: 이전 프레임과 1초 이상 차이 = 사용자 카메라 이탈 후 복귀
        // → 움직임 타이머 리셋하여 잘못된 inactivity timeout 방지
        if (lastFrameMs > 0L && now - lastFrameMs > FRAME_GAP_THRESHOLD_MS) {
            lastMovementMs = now
            lastMetricValue = result.currentMetric
        }
        lastFrameMs = now

        // 움직임 감지 (정적 종료 판단)
        if (kotlin.math.abs(result.currentMetric - lastMetricValue) > 0.5f) {
            lastMovementMs = now
            lastMetricValue = result.currentMetric
        }

        // 운동의 "extreme" 추적: ChairStand(#7)는 작을수록 좋음(min), 나머지는 클수록 좋음(max)
        if (result.state == EngineState.IN_MOTION) {
            val isDecreasing = currentExerciseId == 7
            currentRepPeak = when {
                currentRepPeak.isNaN() -> result.currentMetric
                isDecreasing -> minOf(currentRepPeak, result.currentMetric)
                else -> maxOf(currentRepPeak, result.currentMetric)
            }
        }
        if (result.isCountIncremented && !engine.isInCalibration) {
            if (!currentRepPeak.isNaN()) repPeakMetrics.add(currentRepPeak)
            currentRepPeak = Float.NaN
            lastMovementMs = now
        }
        lastRepCount = result.count

        _uiState.value = ExerciseUiState.Running(
            count = result.count,
            targetCount = engine.targetCount,
            hasError = result.hasError,
            errorMessage = result.errorMessage,
            isCoachingCue = result.isCoachingCue,
            coachingCueMessage = result.coachingCueMessage,
            isCalibrating = result.state == EngineState.CALIBRATING,
            engineState = result.state,
            currentMetric = result.currentMetric,
            bilateralSide = when {
                !isBilateral -> null
                sidePhase == SidePhase.LEFT -> "왼쪽"
                sidePhase == SidePhase.RIGHT -> "오른쪽"
                else -> null
            }
        )

        if (!calibrationPrbSaved && result.state != EngineState.CALIBRATING && engine.measuredCalibrationPRB > 0f) {
            calibrationPrbSaved = true
            currentPrb = engine.measuredCalibrationPRB
            saveCalibratedPRB(engine)
        }

        // 4초 이상 정적 → 자동 종료. 균형 운동(#8)은 정지 자세를 유지하므로 제외.
        if (!isCompleted && !engine.isInCalibration && currentExerciseId != 8 &&
            now - lastMovementMs > NO_MOTION_TIMEOUT_MS) {
            isCompleted = true
            // 양측 운동 진행 중이면 현재까지의 카운트를 보존 (데이터 손실 방지)
            if (isBilateral) {
                when (sidePhase) {
                    SidePhase.LEFT -> leftCompletedCount = engine.currentCount
                    SidePhase.RIGHT -> rightCompletedCount = engine.currentCount
                    else -> Unit
                }
            }
            completeExercise(engine, autoEndedByInactivity = true)
            return
        }

        // 목표 횟수 달성 → 양측 처리 또는 완료
        // 1.5초 delay: "열" 카운트 음성이 발화될 시간 확보 (즉시 다음 단계 전환하면 cut off)
        if (result.count >= perSideTarget && !isCompleted && !isAwaitingFinalCount) {
            isAwaitingFinalCount = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(1500)
                if (!isCompleted) handleSideOrComplete(engine)
            }
        }
    }

    @Volatile private var isAwaitingFinalCount = false

    private fun handleSideOrComplete(engine: ExerciseEngine) {
        if (!isBilateral) {
            isCompleted = true
            completeExercise(engine)
            return
        }
        when (sidePhase) {
            SidePhase.LEFT -> {
                leftCompletedCount = engine.currentCount
                sidePhase = SidePhase.INTERSIDE_REST
                _uiState.value = ExerciseUiState.SideSwitch(
                    exerciseName = engine.exerciseName,
                    fromSide = "왼쪽",
                    toSide = "오른쪽",
                    seconds = SessionFlow.SIDE_REST_SECONDS
                )
                lastMovementMs = System.currentTimeMillis()
            }
            SidePhase.RIGHT -> {
                rightCompletedCount = engine.currentCount
                isCompleted = true
                completeExercise(engine)
            }
            else -> Unit
        }
    }

    /** UI(Fragment)에서 양측 전환 안내(TTS+카운트다운) 끝난 직후 호출 → 오른쪽 측정 시작.
     *  측정 가드(measurementStarted)도 다시 true로 — Fragment가 별도로 startMeasurement() 호출 안 해도 됨. */
    fun startRightSide() {
        val engine = currentEngine ?: return
        engine.reset()
        // 양측 운동: lockedSide flip 등 engine 측 처리 (HipAbduction에서 의미)
        engine.onSideSwitch()
        // PRB는 reset() 후에도 유지되므로 다시 setPRB 호출 불필요
        sidePhase = SidePhase.RIGHT
        errorCount = 0
        isAwaitingFinalCount = false
        lastMovementMs = System.currentTimeMillis()
        lastMetricValue = 0f
        lastFrameMs = 0L
        currentRepPeak = Float.NaN
        measurementStarted = true
        _uiState.value = ExerciseUiState.Ready(
            exerciseName = engine.exerciseName,
            targetCount = perSideTarget,
            bilateralSide = "오른쪽"
        )
    }

    /** Fragment가 양측 전환 안내(짧은 TTS + 카운트다운)를 시작할 때 호출.
     *  안내 도중 frame 처리를 차단하여 잘못된 자세가 카운트되지 않도록 보장. */
    fun pauseMeasurementForSideSwitch() {
        measurementStarted = false
    }

    private fun saveCalibratedPRB(engine: ExerciseEngine) {
        viewModelScope.launch {
            val existing = prbRepository.getLatestPRB(userId, currentExerciseId)
            val newPrb = PRBManager.updateIfHigher(
                engine.measuredCalibrationPRB,
                existing?.prbValue ?: 0f
            )
            if (newPrb > (existing?.prbValue ?: 0f)) {
                prbRepository.savePRB(userId, currentExerciseId, newPrb)
            }
        }
    }

    private fun completeExercise(engine: ExerciseEngine, autoEndedByInactivity: Boolean = false) {
        viewModelScope.launch {
            if (currentSessionId <= 0L) {
                currentSessionId = sessionRepository.startSession(userId)
            }
            val totalAchieved = if (isBilateral) leftCompletedCount + rightCompletedCount.coerceAtLeast(0) else engine.currentCount
            val totalTarget = if (isBilateral) perSideTarget * 2 else engine.targetCount
            val breakdown = QualityScorer.calculate(
                achievedCount = totalAchieved,
                targetCount = totalTarget,
                errorCount = errorCount,
                repMetrics = repPeakMetrics.toList(),
                prbValue = currentPrb,
                metricIsDecreasing = currentExerciseId == 7  // ChairStand: 작을수록 좋음 (D%)
            )
            sessionRepository.saveRecord(
                ExerciseRecord(
                    sessionId = currentSessionId.toInt(),
                    exerciseId = currentExerciseId,
                    setLevel = currentSetLevel,
                    targetCount = totalTarget,
                    achievedCount = totalAchieved,
                    errorCount = errorCount,
                    qualityScore = breakdown.totalScore,
                    completionScore = breakdown.completionScore,
                    formScore = breakdown.formScore,
                    romScore = breakdown.romScore,
                    consistencyScore = breakdown.consistencyScore,
                    avgMetricRatio = breakdown.avgMetricRatio
                )
            )
            _uiState.value = ExerciseUiState.Completed(
                engine.exerciseName,
                breakdown.totalScore,
                breakdown,
                autoEndedByInactivity = autoEndedByInactivity
            )
        }
    }

    fun debugIncrementCount() {
        val engine = currentEngine ?: return
        engine.debugForceCount()
        val count = engine.currentCount
        if (count >= perSideTarget) {
            handleSideOrComplete(engine)
        } else {
            _uiState.value = ExerciseUiState.Running(
                count = count,
                targetCount = engine.targetCount,
                hasError = false,
                errorMessage = null,
                isCoachingCue = false,
                isCalibrating = false,
                engineState = EngineState.IDLE,
                bilateralSide = if (isBilateral && sidePhase == SidePhase.LEFT) "왼쪽"
                                else if (isBilateral && sidePhase == SidePhase.RIGHT) "오른쪽" else null
            )
        }
    }

    fun reset() {
        isReady = false
        measurementStarted = false
        isAwaitingFinalCount = false
        currentEngine?.reset()
        currentEngine = null
        errorCount = 0
        isCompleted = false
        calibrationPrbSaved = false
        repPeakMetrics.clear()
        currentRepPeak = Float.NaN
        lastRepCount = 0
        lastFrameMs = 0L
        lastMetricValue = 0f
        sidePhase = SidePhase.LEFT
        leftCompletedCount = 0
        rightCompletedCount = 0
        _uiState.value = ExerciseUiState.Idle
    }

    sealed class ExerciseUiState {
        object Idle : ExerciseUiState()
        data class Ready(
            val exerciseName: String,
            val targetCount: Int,
            val bilateralSide: String? = null
        ) : ExerciseUiState()
        data class SideSwitch(
            val exerciseName: String,
            val fromSide: String,
            val toSide: String,
            val seconds: Int
        ) : ExerciseUiState()
        data class Completed(
            val exerciseName: String,
            val qualityScore: Int = 0,
            val breakdown: QualityScorer.QualityBreakdown? = null,
            val autoEndedByInactivity: Boolean = false
        ) : ExerciseUiState()
        data class Running(
            val count: Int,
            val targetCount: Int,
            val hasError: Boolean,
            val errorMessage: String?,
            val isCoachingCue: Boolean,
            val coachingCueMessage: String? = null,
            val isCalibrating: Boolean,
            val engineState: EngineState,
            val currentMetric: Float = 0f,
            val bilateralSide: String? = null
        ) : ExerciseUiState()
    }
}
