package com.fallzero.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fallzero.app.data.algorithm.STEADIScorer
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExamResult
import com.fallzero.app.data.repository.ExamRepository
import com.fallzero.app.data.repository.UserRepository
import com.fallzero.app.pose.engine.BalanceEngine
import com.fallzero.app.pose.engine.ChairStandEngine
import com.fallzero.app.pose.engine.EngineState
import com.fallzero.app.pose.engine.FrameResult
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 검사 세션 ViewModel — SessionFlow 기반으로 단계별 자동 진행.
 *
 * 두 개의 독립 모드가 있습니다:
 * - BALANCE: 4단계 균형 검사를 자동으로 순차 실행 (각 단계 최대 30초 시도)
 * - CHAIR_STAND: 30초 의자 일어서기 자동 실행
 *
 * 두 모드의 결과는 동일한 ViewModel 인스턴스에 누적되며, 마지막에 [finalize]를 호출하면
 * STEADI 위험 등급을 계산하고 ExamResult를 저장합니다.
 */
class ExamViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
    private val userId get() = prefs.getInt("user_id", 0)

    private val examRepository = ExamRepository(
        FallZeroDatabase.getInstance(application).examResultDao()
    )
    private val userRepository = UserRepository(
        FallZeroDatabase.getInstance(application).userDao()
    )

    private val _phase = MutableStateFlow<ExamPhase>(ExamPhase.Idle)
    val phase: StateFlow<ExamPhase> = _phase

    // 누적 결과
    private var chairCount = 0
    private var balanceStageReached = 0
    private var tandemTimeSec = 0f
    private var lastCompletedResult: ExamResult? = null
    private var lastRiskAssessment: STEADIScorer.ExamRiskResult? = null

    // 의자 검사 엔진
    private val chairStandEngine = ChairStandEngine(targetCount = 99, examMode = true)
    private var chairStandTimerJob: Job? = null

    // 균형 검사 엔진
    private var balanceEngine = BalanceEngine(targetCount = 1, stage = 1)
    private var balanceStageTimerJob: Job? = null
    private var currentBalanceStage = 0

    companion object {
        const val CHAIR_STAND_DURATION_MS = 30_000L
        const val BALANCE_STAGE_MAX_ATTEMPT_MS = 30_000L
        const val BALANCE_PREPARE_DURATION_MS = 16_000L  // TTS 대기 + 여유 + 3,2,1 카운트다운(~4초) — 충분히 넉넉하게
        // CDC STEADI 4단계 균형 검사: 모든 단계 10초 목표.
        val BALANCE_TARGET_SECS = mapOf(1 to 10f, 2 to 10f, 3 to 10f, 4 to 10f)
    }

    /** 균형 검사 시작 (SessionFlow EXAM_BALANCE 단계에서 호출) */
    fun startBalanceFlow() {
        // 이전 검사 결과 클리어 (검사 재실시 시 결과 화면에 stale 데이터 표시 방지)
        lastCompletedResult = null
        lastRiskAssessment = null
        // 이전 검사의 dangling timer가 있으면 취소
        chairStandTimerJob?.cancel()
        balanceStageTimerJob?.cancel()
        chairCount = 0
        balanceStageReached = 0
        tandemTimeSec = 0f
        currentBalanceStage = 0
        startNextBalanceStage()
    }

    private fun startNextBalanceStage() {
        currentBalanceStage++
        if (currentBalanceStage > 4) {
            _phase.value = ExamPhase.BalanceComplete(balanceStageReached, tandemTimeSec)
            return
        }
        // 자세 준비 단계: Fragment가 TTS+카운트다운 완료 후 startBalanceMeasurementNow() 호출
        // (ViewModel 타이머 없음 — Fragment가 타이밍 제어)
        val stageNo = currentBalanceStage
        _phase.value = ExamPhase.BalancePrepare(
            stage = stageNo,
            stageName = getBalanceStageName(stageNo),
            stageInstruction = getBalanceStageInstruction(stageNo)
        )
    }

    private fun actuallyStartBalanceStage(stageNo: Int) {
        val targetSec = BALANCE_TARGET_SECS[stageNo] ?: 10f
        // 검사 모드: CDC STEADI 목표(10초)를 직접 전달 (훈련용 BalanceProgressionManager 사용 안 함)
        balanceEngine = BalanceEngine(targetCount = 1, stage = stageNo, overrideTargetTimeSec = targetSec)
        balanceEngine.setPRB(0f)
        _phase.value = ExamPhase.Balance(
            stage = stageNo,
            elapsedSec = 0f,
            targetSec = targetSec,
            isStable = true
        )
        // 30초 attempt timer — 통과하지 못하면 자동 다음
        balanceStageTimerJob?.cancel()
        balanceStageTimerJob = viewModelScope.launch {
            delay(BALANCE_STAGE_MAX_ATTEMPT_MS)
            if (currentBalanceStage == stageNo && _phase.value is ExamPhase.Balance) {
                if (stageNo == 3) tandemTimeSec = 0f
                startNextBalanceStage()
            }
        }
    }

    /** 각 균형 단계의 자세 지시 (고령자 친화적) */
    private fun getBalanceStageInstruction(stage: Int): String = when (stage) {
        1 -> "양쪽 발을 나란히 모아 붙이고\n두 팔은 가슴에 교차해주세요.\n\n10초 동안 그대로 서 있으면 됩니다."
        2 -> "한쪽 발 뒤꿈치를\n다른 발 엄지발가락 옆에 놓아주세요.\n\n10초 동안 그대로 서 있으면 됩니다."
        3 -> "한쪽 발 뒤꿈치를\n다른 발 발끝 바로 앞에 일렬로 놓아주세요.\n\n10초 동안 그대로 서 있으면 됩니다."
        4 -> "한쪽 발을 들어 올려\n한 발로만 서주세요.\n\n10초 동안 그대로 서 있으면 됩니다."
        else -> "자세를 준비해주세요."
    }

    /** 의자 일어서기 시작 (SessionFlow EXAM_CHAIR_STAND 단계에서 호출) */
    fun startChairStand() {
        chairStandEngine.reset()
        chairCount = 0
        val startTime = System.currentTimeMillis()
        chairStandTimerJob?.cancel()
        chairStandTimerJob = viewModelScope.launch {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                _phase.value = ExamPhase.ChairStand(elapsed, chairCount, isRunning = true)
                if (elapsed >= CHAIR_STAND_DURATION_MS) break
                delay(100)
            }
            chairCount = chairStandEngine.currentCount
            _phase.value = ExamPhase.ChairStandComplete(chairCount)
        }
    }

    fun processLandmarks(landmarks: List<NormalizedLandmark>) {
        when (val p = _phase.value) {
            is ExamPhase.ChairStand -> {
                if (!p.isRunning) return
                val result: FrameResult = chairStandEngine.processLandmarks(landmarks)
                if (result.isCountIncremented) chairCount = result.count
            }
            is ExamPhase.BalancePrepare -> {
                // 준비 중 — 엔진 처리 안 함 (사용자가 자세를 준비하는 시간)
            }
            is ExamPhase.Balance -> {
                val result: FrameResult = balanceEngine.processLandmarks(landmarks)
                val elapsed = result.currentMetric
                val isStableNow = result.state == EngineState.IN_MOTION
                _phase.value = p.copy(
                    elapsedSec = elapsed,
                    isStable = isStableNow,
                    errorHint = result.errorMessage  // "발을 더 모아주세요" 등
                )
                if (result.isCountIncremented) {
                    // 단계 통과
                    balanceStageReached = currentBalanceStage
                    if (currentBalanceStage == 3) tandemTimeSec = elapsed
                    balanceStageTimerJob?.cancel()
                    startNextBalanceStage()
                }
            }
            else -> {}
        }
    }

    /** 의자 검사 동적 임계값 설정 (ExamFragment에서 3초 서있는 M 평균값 전달) */
    fun calibrateChairStand(standingM: Float) {
        chairStandEngine.calibrateStanding(standingM)
    }

    /** Fragment의 카운트다운 완료 후 호출 — 즉시 균형 측정 시작 (ViewModel의 백업 타이머 취소) */
    fun startBalanceMeasurementNow() {
        balanceStageTimerJob?.cancel()
        actuallyStartBalanceStage(currentBalanceStage)
    }

    /** 외부에서 단계 이름 조회 (ExamFragment 등) */
    fun getBalanceStageName(stage: Int): String = when (stage) {
        1 -> "두 발 나란히 서기"
        2 -> "반탠덤 서기"
        3 -> "탠덤 서기 (일렬로)"
        4 -> "한 발로 서기"
        else -> "${stage}단계"
    }

    /** 모든 검사 끝났을 때 결과 저장 (ExamResultFragment 진입 직전 호출) */
    fun finalizeAndSave() {
        viewModelScope.launch {
            val user = userRepository.getUserById(userId) ?: return@launch
            val risk = STEADIScorer.evaluate(
                user.steadiQ1, user.steadiQ2, user.steadiQ3,
                user.age, user.gender,
                chairStandCount = chairCount,
                tandemTimeSec = tandemTimeSec
            )
            val result = ExamResult(
                userId = userId,
                chairStandCount = chairCount,
                chairStandNorm = risk.chairStandNorm,
                isHighRiskChairStand = risk.isChairStandHighRisk,
                balanceStageReached = balanceStageReached,
                tandemTimeSec = tandemTimeSec,
                isHighRiskBalance = risk.isBalanceHighRisk,
                isHighRiskSurvey = risk.isSurveyHighRisk,
                finalRiskLevel = risk.finalRiskLevel
            )
            examRepository.saveResult(result)
            lastCompletedResult = result
            lastRiskAssessment = risk
            _phase.value = ExamPhase.Completed(result, risk)
        }
    }

    /** 의자 검사 진입 전 stale balance 상태 클리어 */
    fun resetPhaseToIdle() {
        _phase.value = ExamPhase.Idle
    }

    fun resetForNewSession() {
        chairCount = 0
        balanceStageReached = 0
        tandemTimeSec = 0f
        currentBalanceStage = 0
        chairStandEngine.reset()
        balanceStageTimerJob?.cancel()
        chairStandTimerJob?.cancel()
        _phase.value = ExamPhase.Idle
    }

    fun getCompletedResult(): ExamPhase.Completed? {
        val r = lastCompletedResult
        val a = lastRiskAssessment
        return if (r != null && a != null) ExamPhase.Completed(r, a) else null
    }

    sealed class ExamPhase {
        object Idle : ExamPhase()

        data class ChairStand(
            val elapsedMs: Long,
            val count: Int,
            val isRunning: Boolean,
            val errorHint: String? = null
        ) : ExamPhase() {
            val remainingSec: Int get() = ((30_000L - elapsedMs) / 1000).toInt().coerceAtLeast(0)
        }

        data class ChairStandComplete(val count: Int) : ExamPhase()

        /** 균형 단계 진입 전 자세 안내 (5초 준비 시간) */
        data class BalancePrepare(
            val stage: Int,
            val stageName: String,
            val stageInstruction: String
        ) : ExamPhase()

        data class Balance(
            val stage: Int,
            val elapsedSec: Float,
            val targetSec: Float,
            val isStable: Boolean,
            val errorHint: String? = null  // 자세 피드백 ("발을 더 모아주세요" 등)
        ) : ExamPhase()

        data class BalanceComplete(val highestStage: Int, val tandemTimeSec: Float) : ExamPhase()

        data class Completed(
            val result: ExamResult,
            val riskAssessment: STEADIScorer.ExamRiskResult
        ) : ExamPhase()
    }
}
