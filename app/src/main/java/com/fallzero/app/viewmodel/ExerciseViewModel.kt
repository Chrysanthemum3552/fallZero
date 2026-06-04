package com.fallzero.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.algorithm.BalanceProgressionManager
import com.fallzero.app.data.algorithm.PRBManager
import com.fallzero.app.data.algorithm.ProgressionManager
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

    // 단순 진급 룰용 — 반복별 음성피드백 발생 여부.
    //   currentRepHadFeedback: 현재 진행 중인 반복(아직 카운트 미완료) 안에서 errorMessage 또는
    //                          coachingCueMessage가 한 번이라도 검출됐는지.
    //   allRepFeedbackFlags: 카운트가 확정된 시점에 currentRepHadFeedback을 push.
    //                          true=피드백 발생, false=무피드백(잘 수행). 양측 운동은 좌→우 누적.
    private var currentRepHadFeedback = false
    private val allRepFeedbackFlags = mutableListOf<Boolean>()

    fun initExercise(engine: ExerciseEngine, exerciseId: Int, setLevel: Int, existingSessionId: Long = -1L) {
        // 초기화 race 방지: setPRB 호출 전까지 frame 처리 차단
        isReady = false
        // 안내 화면 끝(=Fragment의 startMeasurement 호출)까지 frame 차단
        measurementStarted = false
        isAwaitingFinalCount = false
        currentEngine = engine
        currentExerciseId = exerciseId
        currentSetLevel = setLevel
        calibrationPrbSaved = false
        isCompleted = false
        allRepFeedbackFlags.clear()
        currentRepHadFeedback = false
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
            // 풀세션 진행 중이면 동일 TrainingSession을 재사용해야 8개 운동 record가 한 세션에 묶임.
            // markFullSessionComplete()/단일 운동 완료 시 currentSessionId=-1로 비워두기 때문에
            // 새 세션 시작 때는 else 분기로 새 row가 만들어진다.
            currentSessionId = when {
                existingSessionId > 0L -> existingSessionId
                currentSessionId > 0L  -> currentSessionId
                else                   -> sessionRepository.startSession(userId)
            }

            val savedPrb = prbRepository.getLatestPRB(userId, exerciseId)
            currentPrb = savedPrb?.prbValue ?: 0f
            engine.setPRB(currentPrb)
            isReady = true
            _uiState.value = ExerciseUiState.Ready(
                engine.exerciseName,
                engine.targetCount,
                bilateralSide = if (isBilateral) "한쪽" else null
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

        // 단순 진급 룰: 진행 중인 반복 안에서 어떤 음성 안내라도 검출됐는지 누적.
        // 캘리브레이션 중에는 추적 안 함 (본 운동 카운트만 집계).
        if (!engine.isInCalibration && (result.errorMessage != null || result.coachingCueMessage != null)) {
            currentRepHadFeedback = true
        }

        val now = System.currentTimeMillis()

        // 프레임 갭 감지: 이전 프레임과 1초 이상 차이 = 사용자 카메라 이탈 후 복귀
        // → 움직임 타이머 리셋하여 잘못된 inactivity timeout 방지
        if (lastFrameMs > 0L && now - lastFrameMs > FRAME_GAP_THRESHOLD_MS) {
            lastMovementMs = now
            lastMetricValue = result.currentMetric
        }
        lastFrameMs = now

        // 움직임 감지 (정적 종료 판단) — engine 별 임계값 사용 (ToeRaise/CalfRaise 등 작은 메트릭은 0.005 등)
        val movementThr = engine.movementThreshold
        val metricDelta = kotlin.math.abs(result.currentMetric - lastMetricValue)
        if (metricDelta > movementThr) {
            lastMovementMs = now
            lastMetricValue = result.currentMetric
        }
        // 진단 로그 (ChairStand 한정) — inactivity timeout 임박 시 경고. 4초 임계값에 가까워지면 알림.
        if (currentExerciseId == 7 && !engine.isInCalibration && !isCompleted) {
            val sinceMovement = now - lastMovementMs
            if (sinceMovement > 2500L) {
                android.util.Log.w("ChairStandDiag",
                    "INACTIVITY warning: sinceMovement=%dms (timeout @%dms) metricDelta=%.3f movementThr=%.3f curM=%.1f lastM=%.1f".format(
                        sinceMovement, NO_MOTION_TIMEOUT_MS, metricDelta, movementThr,
                        result.currentMetric, lastMetricValue))
            }
        }

        if (result.isCountIncremented && !engine.isInCalibration) {
            lastMovementMs = now
            // 단순 진급 룰: 이 반복(직전 카운트~지금)에서 피드백이 발생했는지 push 후 리셋.
            //   카운트와 같은 frame에서 emitted된 errorMessage도 위 누적 단계에서 잡힘.
            allRepFeedbackFlags.add(currentRepHadFeedback)
            currentRepHadFeedback = false
        }

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
                sidePhase == SidePhase.LEFT -> "한쪽"
                sidePhase == SidePhase.RIGHT -> "다른쪽"
                else -> null
            },
            calibrationRepCompleted = result.calibrationRepCompleted,
            calibrationReps = result.calibrationReps
        )

        if (!calibrationPrbSaved && result.state != EngineState.CALIBRATING && engine.measuredCalibrationPRB > 0f) {
            calibrationPrbSaved = true
            currentPrb = engine.measuredCalibrationPRB
            saveCalibratedPRB(engine)
            // 본 운동 진입 시점에 inactivity timer 리셋 — 사용자가 calibration 직후 잠시 정지해도
            // 곧바로 4초 timeout으로 종료되지 않도록 grace period 보장.
            lastMovementMs = now
            lastMetricValue = result.currentMetric
        }

        // inactivity 자동 종료 — engine별 timeout(default 4초, CalfRaise/ToeRaise 8초). 균형 운동(#8) 제외.
        val inactivityTimeoutMs = engine.inactivityTimeoutMs
        if (!isCompleted && !engine.isInCalibration && currentExerciseId != 8 &&
            now - lastMovementMs > inactivityTimeoutMs) {
            // 진단: 어떤 운동에서 timeout으로 끝났는지 명시 (ChairStand 카운트 누락 원인 추적)
            android.util.Log.w("ChairStandDiag",
                "INACTIVITY TIMEOUT FIRED! exerciseId=$currentExerciseId sinceMovement=${now - lastMovementMs}ms timeout=${inactivityTimeoutMs}ms curCount=${engine.currentCount} curM=${result.currentMetric} → completeExercise(autoEnded=true)")
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
        // 즉시 measurementStarted=false: 1.5초 동안 wrong-leg 같은 잘못된 detection 발생 방지
        if (result.count >= perSideTarget && !isCompleted && !isAwaitingFinalCount) {
            isAwaitingFinalCount = true
            measurementStarted = false  // 즉시 detection 차단 (사용자 명시: 10회 직후 wrong-leg 경고 방지)
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
                    fromSide = "한쪽",
                    toSide = "다른쪽",
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
        isAwaitingFinalCount = false
        lastMovementMs = System.currentTimeMillis()
        lastMetricValue = 0f
        lastFrameMs = 0L
        measurementStarted = true
        _uiState.value = ExerciseUiState.Ready(
            exerciseName = engine.exerciseName,
            targetCount = perSideTarget,
            bilateralSide = "다른쪽"
        )
    }

    /** Fragment가 양측 전환 안내(짧은 TTS + 카운트다운)를 시작할 때 호출.
     *  안내 도중 frame 처리를 차단하여 잘못된 자세가 카운트되지 않도록 보장. */
    fun pauseMeasurementForSideSwitch() {
        measurementStarted = false
    }

    /** Fragment에서 매 frame 가이드 정보 요청 — 현재 엔진의 getGuide 반환. */
    fun getGuide(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>):
            com.fallzero.app.ui.overlay.ExerciseGuide? {
        return currentEngine?.getGuide(landmarks)
    }

    /** Fragment가 운동 시작 전에 캘리브레이션 필요 여부 확인.
     *  - true: 이 운동의 PRB가 없음 → 캘리브레이션 모드로 진입, 안내 후 즉시 startMeasurement (연습 2회 후 자체적으로 3,2,1 트리거)
     *  - false: PRB 있음 → 캘리브레이션 안 함 → 안내 후 즉시 본 운동, 3,2,1 카운트다운 별도로 호출 필요 (Q3)
     *  initExercise 후 isReady=true 진입한 다음에만 신뢰 가능. */
    fun isCalibrationRequired(): Boolean = currentEngine?.isInCalibration ?: false

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

            // 단순 진급 룰의 단일 입력 — 반복별 피드백 발생 여부 CSV.
            // 미완료(자동 종료 등) 시점에 진행 중이던 미카운트 반복은 push 안 함 (불완전 데이터 제외).
            val repFeedbackFlagsCsv = allRepFeedbackFlags.joinToString(",") { if (it) "1" else "0" }

            sessionRepository.saveRecord(
                ExerciseRecord(
                    sessionId = currentSessionId.toInt(),
                    exerciseId = currentExerciseId,
                    setLevel = currentSetLevel,
                    targetCount = totalTarget,
                    achievedCount = totalAchieved,
                    repFeedbackFlags = repFeedbackFlagsCsv
                )
            )
            val progressionResult = runCatching { evaluateProgression() }
                .onFailure { android.util.Log.w("Progression", "evaluateProgression failed", it) }
                .getOrNull()
            _uiState.value = ExerciseUiState.Completed(
                exerciseName = engine.exerciseName,
                autoEndedByInactivity = autoEndedByInactivity,
                progressionResult = progressionResult
            )
            // 단일 운동(연속 실행 시 같은 세션에 record가 합쳐지지 않도록) 종료 시 sid 비우기.
            // 풀세션은 markFullSessionComplete()가 sid 초기화 담당 → 그 전까지는 8개 운동이 같은 sid 공유.
            if (!SessionFlow.isFullExerciseSession) {
                currentSessionId = -1L
            }
        }
    }

    /**
     * 진급 판단 + setLevel 갱신. completeExercise 내 saveRecord 직후 호출.
     *   - 균형 운동(#8): 통과 시 current_set_level 을 다음 stage로 갱신 (BalanceEngine이 즉시 반영).
     *   - 근력 운동(#1~#7): 통과 시 per-exercise 키 set_level_ex_<id> 를 2로 갱신.
     *     (현재 코드에서 근력 엔진은 setLevel을 사용하지 않으므로 데이터만 저장 — 멀티세트 훈련 UI 추가 시 활용 예정.)
     * @return 진급이 실제 발생했으면 ProgressionResult, 아니면 null. UI 알림용.
     */
    private suspend fun evaluateProgression(): ProgressionResult? {
        val records = sessionRepository.getRecentRecordsByExercise(userId, currentExerciseId, 30)
        val isBalance = currentExerciseId == 8
        val shouldProgress = if (isBalance) {
            BalanceProgressionManager.shouldProgressStage(records)
        } else {
            ProgressionManager.shouldProgressToTwoSets(records)
        }
        if (!shouldProgress) {
            android.util.Log.d("Progression",
                "exerciseId=$currentExerciseId stays at setLevel=$currentSetLevel (records=${records.size})")
            return null
        }
        if (isBalance) {
            val nextStage = BalanceProgressionManager.getNextStage(currentSetLevel)
            if (nextStage > currentSetLevel) {
                prefs.edit().putInt("current_set_level", nextStage).apply()
                android.util.Log.i("Progression",
                    "Balance progressed: stage $currentSetLevel → $nextStage")
                val nextDesc = BalanceProgressionManager.getLevel(nextStage).let {
                    "${it.description} ${it.targetTimeSec.toInt()}초"
                }
                return ProgressionResult(
                    isBalance = true,
                    previousLevel = currentSetLevel,
                    newLevel = nextStage,
                    message = "축하해요! 균형 훈련이 ${nextDesc} 단계로 진급했어요."
                )
            } else {
                android.util.Log.i("Progression", "Balance already at final stage ($currentSetLevel)")
                return null
            }
        } else {
            val key = "set_level_ex_$currentExerciseId"
            val currentEx = prefs.getInt(key, 1)
            if (currentEx < 2) {
                prefs.edit().putInt(key, 2).apply()
                android.util.Log.i("Progression",
                    "Strength exerciseId=$currentExerciseId progressed: 1 set → 2 sets")
                val name = currentEngine?.exerciseName ?: "이 운동"
                return ProgressionResult(
                    isBalance = false,
                    previousLevel = currentEx,
                    newLevel = 2,
                    message = "축하해요! $name${subjectParticle(name)} 2세트로 진급했어요."
                )
            }
            return null
        }
    }

    /** 한글 받침 유무에 따라 주격 조사("이"/"가") 자동 선택. */
    private fun subjectParticle(noun: String): String {
        val last = noun.lastOrNull() ?: return "이"
        val code = last.code
        if (code !in 0xAC00..0xD7A3) return "이"  // 한글 음절이 아니면 default
        val hasFinalConsonant = (code - 0xAC00) % 28 != 0
        return if (hasFinalConsonant) "이" else "가"
    }

    /** 진급 알림용 UI 데이터. UI는 Completed 상태의 progressionResult가 null이 아닐 때만 알림 표시. */
    data class ProgressionResult(
        val isBalance: Boolean,
        val previousLevel: Int,
        val newLevel: Int,
        val message: String
    )

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
                bilateralSide = if (isBilateral && sidePhase == SidePhase.LEFT) "한쪽"
                                else if (isBilateral && sidePhase == SidePhase.RIGHT) "다른쪽" else null
            )
        }
    }

    fun reset() {
        isReady = false
        measurementStarted = false
        isAwaitingFinalCount = false
        currentEngine?.reset()
        currentEngine = null
        isCompleted = false
        calibrationPrbSaved = false
        lastFrameMs = 0L
        lastMetricValue = 0f
        sidePhase = SidePhase.LEFT
        leftCompletedCount = 0
        rightCompletedCount = 0
        allRepFeedbackFlags.clear()
        currentRepHadFeedback = false
        _uiState.value = ExerciseUiState.Idle
    }

    /** Q8a/Q8b — 풀 세션(전체 8개 운동) 완료 시 호출. 마지막 운동의 TrainingSession을
     *  isCompleted=1로 표시 → HomeFragment 대시보드 "오늘 운동: 완료!" 갱신 */
    fun markFullSessionComplete() {
        val sid = currentSessionId
        if (sid <= 0L) return
        // 다음 풀세션이 옛 sid에 record를 덧붙이지 않도록 즉시 비움.
        // (sid 변수에 이미 값 저장됨 → coroutine에서 안전하게 사용)
        currentSessionId = -1L
        viewModelScope.launch {
            try { sessionRepository.markSessionCompleted(sid.toInt()) }
            catch (_: Exception) {}
        }
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
            val autoEndedByInactivity: Boolean = false,
            val progressionResult: ProgressionResult? = null
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
            val bilateralSide: String? = null,
            val calibrationRepCompleted: Boolean = false,
            val calibrationReps: Int = 0
        ) : ExerciseUiState()
    }
}
