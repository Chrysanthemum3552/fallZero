package com.fallzero.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExamResult
import com.fallzero.app.data.db.entity.TrainingSession
import com.fallzero.app.data.repository.ExamRepository
import com.fallzero.app.data.repository.PRBRepository
import com.fallzero.app.data.repository.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보고서 3페이지 데이터 뷰모델
 *
 * Page 1 (ReportFragment)       — 최근 검사 결과, 낙상위험 판정, 이전 비교
 * Page 2 (ReportPage2Fragment)  — 주간 훈련 이행률, 세션 캘린더
 * Page 3 (ReportPage3Fragment)  — 운동별 PRB 추이 차트
 */
class ReportViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
    private val userId get() = prefs.getInt("user_id", 0)

    private val db = FallZeroDatabase.getInstance(application)
    private val examRepository = ExamRepository(db.examResultDao())
    private val sessionRepository = SessionRepository(db.sessionDao())
    private val prbRepository = PRBRepository(db.prbDao())

    // ──── Page 1 ────
    private val _page1 = MutableStateFlow<Page1Data?>(null)
    val page1: StateFlow<Page1Data?> = _page1

    // ──── Page 2 ────
    private val _page2 = MutableStateFlow<Page2Data?>(null)
    val page2: StateFlow<Page2Data?> = _page2

    // ──── Page 3 ────
    private val _page3 = MutableStateFlow<Page3Data?>(null)
    val page3: StateFlow<Page3Data?> = _page3

    init {
        loadAll()
    }

    private fun loadAll() {
        viewModelScope.launch {
            loadPage1()
            loadPage2()
            loadPage3()
        }
    }

    // ──────────────────── Page 1: 검사 결과 ────────────────────

    private suspend fun loadPage1() {
        val latest = examRepository.getLatestResult(userId)
        val first = db.examResultDao().getFirstResult(userId)
        _page1.value = Page1Data(latestResult = latest, firstResult = first)
    }

    // ──────────────────── Page 2: 훈련 이행률 ────────────────────

    private suspend fun loadPage2() {
        // 최근 28일 (4주) 세션 이력
        val sessions = sessionRepository.getRecentCompletedSessions(userId, limit = 28)
        val dateFormat = SimpleDateFormat("MM/dd", Locale.KOREAN)
        val completedDates = sessions.map { dateFormat.format(Date(it.startedAt)) }.toSet()

        // 주간 이행률: 권고 횟수 3회/주 대비 실제 완료 세션
        val weeklyRate = if (sessions.size >= 3) {
            val recentWeek = sessions.take(7)
            "이번 주 ${recentWeek.size}회 완료"
        } else {
            "이번 주 ${sessions.size}회 완료"
        }

        _page2.value = Page2Data(
            recentSessions = sessions,
            completedDates = completedDates,
            weeklyAdherenceText = weeklyRate,
            totalSessions = sessions.size
        )
    }

    // ──────────────────── Page 3: PRB 추이 ────────────────────

    private suspend fun loadPage3() {
        // 각 운동별 최신 PRB 조회
        val exerciseNames = mapOf(
            1 to "대퇴사두근 강화", 2 to "고관절 외전", 3 to "슬굴곡근 강화",
            4 to "발뒤꿈치 들기", 5 to "발끝 들기", 6 to "무릎 굽히기",
            7 to "앉았다 일어서기", 8 to "균형 훈련"
        )

        val prbEntries = (1..8).mapNotNull { exerciseId ->
            val prb = prbRepository.getLatestPRB(userId, exerciseId)
            if (prb != null) {
                PrbEntry(
                    exerciseId = exerciseId,
                    exerciseName = exerciseNames[exerciseId] ?: "운동 $exerciseId",
                    prbValue = prb.prbValue,
                    measuredAt = prb.measuredAt
                )
            } else null
        }

        _page3.value = Page3Data(prbEntries = prbEntries)
    }

    // ──────────────────── Data Classes ────────────────────

    data class Page1Data(
        val latestResult: ExamResult?,
        val firstResult: ExamResult?
    ) {
        val isImproved: Boolean get() {
            if (latestResult == null || firstResult == null) return false
            return latestResult.finalRiskLevel == "low" && firstResult.finalRiskLevel == "high"
        }
    }

    data class Page2Data(
        val recentSessions: List<TrainingSession>,
        val completedDates: Set<String>,
        val weeklyAdherenceText: String,
        val totalSessions: Int
    )

    data class Page3Data(val prbEntries: List<PrbEntry>)

    data class PrbEntry(
        val exerciseId: Int,
        val exerciseName: String,
        val prbValue: Float,
        val measuredAt: Long
    )
}
