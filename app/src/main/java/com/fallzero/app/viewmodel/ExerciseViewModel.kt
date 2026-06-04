package com.fallzero.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.algorithm.BalanceProgressionManager
import com.fallzero.app.data.algorithm.PRBManager
import com.fallzero.app.data.algorithm.ProgressionManager
import com.fallzero.app.data.algorithm.QualityScorer
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.data.repository.PRBRepository
import com.fallzero.app.data.repository.SessionRepository
import com.fallzero.app.pose.engine.BalanceEngine
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

    // PDF §5·§6 개선 진급 알고리즘용 — 운동 전체 duration과 rep별 timestamp 수집.
    // bilateral 양측 운동도 ViewModel 레벨에서 누적되므로 reset 사이 손실 없음.
    private var exerciseStartMs: Long = 0L
    private val allRepTimestamps = mutableListOf<Long>()

    // 회당(per-rep) 마크 — 운동 기록 동그라미용. null=초록(자세오류無), non-null=주황 사유(짧은 라벨).
    // bilateral은 좌/우 분리, 비양방은 single. startRightSide()에서 reset되지 않고 운동 끝까지 누적.
    private val repMarksLeft = mutableListOf<String?>()
    private val repMarksRight = mutableListOf<String?>()
    private val repMarksSingle = mutableListOf<String?>()
    // 균형(#8) 좌/우 유지시간(초)
    private var balanceLeftHoldSec = 0f
    private var balanceRightHoldSec = 0f

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
        allRepTimestamps.clear()
        repMarksLeft.clear()
        repMarksRight.clear()
        repMarksSingle.clear()
        balanceLeftHoldSec = 0f
        balanceRightHoldSec = 0f
        // 운동 시작 시각 — completeExercise까지의 차이가 durationMs (PDF §6).
        // 안내 화면(measurementStarted=false) 동안도 포함되지만, 모든 사용자에 동일하게 적용되므로
        // 상대 비교(개인의 최근 안정 성공 평균 대비)에 영향 없음.
        exerciseStartMs = System.currentTimeMillis()
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
            // PDF §5 SpeedLossRate 계산용 — 각 카운트 시각 누적.
            // 양측 운동도 engine.reset() 영향 안 받음 (ViewModel이 직접 보관).
            allRepTimestamps.add(now)
            // 회당 마크 적재 (운동 기록 동그라미용): 카운트 시점의 자세 오류를 짧은 라벨로. 없으면 null=초록.
            val repReason = result.errorMessage?.let { shortErrorLabel(it) }
            when {
                !isBilateral -> repMarksSingle.add(repReason)
                sidePhase == SidePhase.LEFT -> repMarksLeft.add(repReason)
                sidePhase == SidePhase.RIGHT -> repMarksRight.add(repReason)
                else -> Unit
            }
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

        // [사용자 요청] 무동작 자동 종료(inactivity → 다음으로 자동 넘김) 로직 제거.
        //   운동은 목표 횟수를 채우거나 사용자가 '정지' 버튼을 눌러야만 종료된다.
        //   (lastMovementMs 추적은 의자 일어서기 '일어나주세요' 안내 등에 계속 사용되므로 유지.)

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
                if (currentExerciseId == 8) balanceLeftHoldSec = (engine as? BalanceEngine)?.bestHoldTimeSec ?: 0f
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
                if (currentExerciseId == 8) balanceRightHoldSec = (engine as? BalanceEngine)?.bestHoldTimeSec ?: 0f
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
            // 균형 운동(#8)은 ROM/일관성 슬롯을 안정성/유지시간으로 재해석. QualityScorer가 운동별 의미를 다르게 계산.
            val isBalance = currentExerciseId == 8
            val balanceEngine = engine as? BalanceEngine
            val balanceWobbleForScorer = if (isBalance) engine.balanceWobble else null
            val bestHoldForScorer = if (isBalance) balanceEngine?.bestHoldTimeSec else null
            val targetHoldForScorer = if (isBalance) BalanceProgressionManager.getTargetTime(currentSetLevel) else null

            // CalfRaise(#4)/ToeRaise(#5)는 motionThreshold가 고정값(0.030/0.022)인데 PRB는 calibration 최댓값.
            // 결과적으로 본 운동에서 PRB 대비 ratio가 항상 낮아 ROM 점수가 부당하게 낮음.
            // ROM 계산용으로만 PRB를 motionThreshold의 약 2배로 cap — 카운트 동작은 영향 없음, ROM 점수만 합리화.
            val prbForScorer = when (currentExerciseId) {
                4 -> currentPrb.coerceAtMost(0.060f)   // CalfRaise: motionThr(0.030) × 2
                5 -> currentPrb.coerceAtMost(0.044f)   // ToeRaise: motionThr(0.022) × 2
                // KneeBend "살짝 굽히기": motionThr=24.5° (cap 35°×0.70). PRB 그대로 쓰면 깊게 굽혀야 ROM 100.
                // ROM 계산용으로 25°로 cap — 카운트된 rep(≥24.5°)은 ratio≥0.98로 ROM ≈100 보장.
                6 -> currentPrb.coerceAtMost(25f)
                else -> currentPrb
            }
            // PDF §5·§6 측정값 — 6지표 통합으로 QualityScorer 계산에도 필요해 먼저 산출.
            val durationMs = if (exerciseStartMs > 0L) System.currentTimeMillis() - exerciseStartMs else 0L
            val speedLossRate = calculateSpeedLossRate(allRepTimestamps)
            val balanceWobble = engine.balanceWobble
            // 시간 효율 점수용 baseline: 과거 성공한 같은 운동의 durationMs 평균. 3개 미만이면 null → score=100.
            val pastRecords = sessionRepository.getRecentRecordsByExercise(userId, currentExerciseId, 30)
            val priorSuccess = pastRecords.filter {
                it.achievedCount >= it.targetCount && it.durationMs > 0L
            }
            val baselineDurationMs: Float? = if (priorSuccess.size >= 3) {
                priorSuccess.map { it.durationMs.toFloat() }.average().toFloat()
            } else null

            // 저장용 오류 횟수 — 회당 마크(좌+우 모두)의 주황 개수와 일치시켜 "동그라미 ↔ 진급 판정"을 정합화.
            // (라이브 errorCount는 startRightSide에서 0으로 리셋되어 양방의 왼쪽 오류가 누락되므로 사용하지 않음.)
            // #8 균형은 detectError 오류가 없어 라이브 errorCount(0)을 그대로 사용.
            val savedErrorCount = if (currentExerciseId == 8) errorCount
                else repMarksLeft.count { it != null } +
                     repMarksRight.count { it != null } +
                     repMarksSingle.count { it != null }

            val breakdown = QualityScorer.calculate(
                achievedCount = totalAchieved,
                targetCount = totalTarget,
                errorCount = savedErrorCount,
                repMetrics = repPeakMetrics.toList(),
                prbValue = prbForScorer,
                metricIsDecreasing = currentExerciseId == 7,  // ChairStand: 작을수록 좋음 (D%)
                balanceWobble = balanceWobbleForScorer,
                bestHoldSec = bestHoldForScorer,
                targetHoldSec = targetHoldForScorer,
                speedLossRate = speedLossRate,
                durationMs = durationMs,
                baselineDurationMs = baselineDurationMs
            )
            val newRecordId = sessionRepository.saveRecord(
                ExerciseRecord(
                    sessionId = currentSessionId.toInt(),
                    exerciseId = currentExerciseId,
                    setLevel = currentSetLevel,
                    targetCount = totalTarget,
                    achievedCount = totalAchieved,
                    errorCount = savedErrorCount,
                    qualityScore = breakdown.totalScore,
                    completionScore = breakdown.completionScore,
                    formScore = breakdown.formScore,
                    romScore = breakdown.romScore,
                    consistencyScore = breakdown.consistencyScore,
                    avgMetricRatio = breakdown.avgMetricRatio,
                    durationMs = durationMs,
                    speedLossRate = speedLossRate,
                    balanceWobble = balanceWobble,
                    repResults = serializeRepResults()
                )
            )
            // PDF 개선 진급 알고리즘 — 기록 저장 후 즉시 평가. 단순한 실패는 무시(데이터 부족 등).
            val progressionResult = runCatching { evaluateProgression() }
                .onFailure { android.util.Log.w("Progression", "evaluateProgression failed", it) }
                .getOrNull()
            // 진급이 발생했으면 방금 저장한 그 기록에 진급 표식 — 운동 기록 화면 "🎉 진급!" 배지용.
            if (progressionResult != null) {
                runCatching { sessionRepository.markRecordPromoted(newRecordId.toInt(), progressionResult.message) }
            }
            _uiState.value = ExerciseUiState.Completed(
                engine.exerciseName,
                breakdown.totalScore,
                breakdown,
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
                    message = "축하해요!\n균형 훈련이 ${nextDesc} 단계로 진급했어요."
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
                    message = "축하해요!\n$name${subjectParticle(name)} 2세트로 진급했어요."
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

    /**
     * PDF §5 SpeedLossRate 계산. 초반 3개와 후반 3개 rep 간격(=RepDuration의 근사)의 중앙값 비교.
     *   RepSpeed = 1 / RepDuration
     *   SpeedLossRate = (초반 속도 - 후반 속도) / 초반 속도
     * 카운트 시각이 N개면 인접 간격은 N-1개. 의미 있는 비교를 위해 최소 7개 timestamp(=6 interval) 필요.
     */
    private fun calculateSpeedLossRate(timestamps: List<Long>): Float {
        if (timestamps.size < 7) return 0f
        val intervals = (1 until timestamps.size).map { timestamps[it] - timestamps[it - 1] }
        if (intervals.size < 6) return 0f
        val firstMedianMs = intervals.take(3).sorted()[1]
        val lastMedianMs = intervals.takeLast(3).sorted()[1]
        if (firstMedianMs <= 0L || lastMedianMs <= 0L) return 0f
        val firstSpeed = 1.0 / firstMedianMs
        val lastSpeed = 1.0 / lastMedianMs
        return ((firstSpeed - lastSpeed) / firstSpeed).toFloat().coerceAtLeast(0f)
    }

    /** 자세 오류 원문 메시지 → 짧은 진단 라벨 (동그라미 옆 표시용). */
    private fun shortErrorLabel(msg: String): String = when {
        msg.contains("반대") -> "반대쪽 다리"
        msg.contains("골반") && msg.contains("기울") -> "골반 기울임"
        msg.contains("상체") -> "상체 기울임"
        msg.contains("골반") && msg.contains("흔들") -> "골반 흔들림"
        msg.contains("허벅지") -> "허벅지 들림"
        msg.contains("무릎") && msg.contains("펴") -> "무릎 굽힘"
        msg.contains("흔들") -> "몸 흔들림"
        else -> "자세 오류"
    }

    /** 회당 마크를 ExerciseRecord.repResults 문자열로 직렬화 (포맷은 ExerciseRecord 주석 참고). */
    private fun serializeRepResults(): String {
        if (currentExerciseId == 8) {
            return "B;L=%.0f;R=%.0f".format(balanceLeftHoldSec, balanceRightHoldSec)
        }
        fun enc(marks: List<String?>): String = marks.joinToString("|") { it ?: "O" }
        return if (isBilateral) {
            "M;L=${enc(repMarksLeft)};R=${enc(repMarksRight)}"
        } else {
            "M;S=${enc(repMarksSingle)}"
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
        allRepTimestamps.clear()
        repMarksLeft.clear()
        repMarksRight.clear()
        repMarksSingle.clear()
        balanceLeftHoldSec = 0f
        balanceRightHoldSec = 0f
        exerciseStartMs = 0L
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
            val qualityScore: Int = 0,
            val breakdown: QualityScorer.QualityBreakdown? = null,
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
