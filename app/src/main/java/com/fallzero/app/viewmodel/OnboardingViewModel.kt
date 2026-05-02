package com.fallzero.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 온보딩 ViewModel — 5개 Fragment(성별/나이/Q1/Q2/Q3)가 답변을 임시 변수에 누적.
 * 마지막 Q3 답변 후 [saveAll]을 호출하면 DB 트랜잭션 1번으로 모든 답변을 저장하고
 * [completionEvent]로 완료 알림.
 *
 * 뒤로가기로 화면을 다시 방문해도 임시 변수가 유지되어 사용자가 재답변 가능.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = UserRepository(
        FallZeroDatabase.getInstance(application).userDao()
    )
    private val prefs = application.getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)

    // ─── 5개 Fragment에서 setter로 누적되는 임시 답변 ───
    var tempGender: String = ""        // "male" or "female"
    var tempAge: Int = 0               // 60~110
    var tempQ1: Boolean = false        // 지난 1년 넘어진 적 있음?
    var tempQ2: Boolean = false        // 서거나 걸을 때 불안정?
    var tempQ3: Boolean = false        // 넘어질까봐 두려움?

    /** Q3 답변 저장 + DB 저장 완료 후 user_id 발행 (Fragment에서 collect → navigate) */
    private val _completionEvent = MutableStateFlow<Int?>(null)
    val completionEvent: StateFlow<Int?> = _completionEvent

    /** 이미 온보딩이 완료된 경우 true → MainActivity에서 HomeFragment로 바로 이동 */
    fun isOnboardingAlreadyComplete(): Boolean {
        return prefs.getBoolean("onboarding_complete", false)
    }

    /** 마지막 Q3 답변 후 호출 — DB 트랜잭션 1번으로 모든 답변을 저장 */
    fun saveAll() {
        viewModelScope.launch {
            val userId = repository.saveUser(tempGender, tempAge).toInt()
            repository.saveSteadiResults(userId, tempQ1, tempQ2, tempQ3)
            repository.updateOnboardingComplete(userId)
            prefs.edit()
                .putBoolean("onboarding_complete", true)
                .putInt("user_id", userId)
                .apply()
            _completionEvent.value = userId
        }
    }
}
