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

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = UserRepository(
        FallZeroDatabase.getInstance(application).userDao()
    )
    private val prefs = application.getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Step1Gender)
    val state: StateFlow<OnboardingState> = _state

    private var gender: String = ""
    private var age: Int = 0
    private var savedUserId: Int = 0

    /** 이미 온보딩이 완료된 경우 true → MainActivity에서 HomeFragment로 바로 이동 */
    fun isOnboardingAlreadyComplete(): Boolean {
        return prefs.getBoolean("onboarding_complete", false)
    }

    /** Step 1: 성별·나이 저장 후 STEADI 설문 단계로 전환 */
    fun saveGenderAndAge(gender: String, age: Int) {
        this.gender = gender
        this.age = age
        viewModelScope.launch {
            savedUserId = repository.saveUser(gender, age).toInt()
            _state.value = OnboardingState.Step2Steadi
        }
    }

    /** Step 2: STEADI 3문항 저장 후 온보딩 완료 */
    fun saveSteadiAndComplete(q1: Boolean, q2: Boolean, q3: Boolean) {
        viewModelScope.launch {
            repository.saveSteadiResults(savedUserId, q1, q2, q3)
            repository.updateOnboardingComplete(savedUserId)
            prefs.edit()
                .putBoolean("onboarding_complete", true)
                .putInt("user_id", savedUserId)
                .apply()
            _state.value = OnboardingState.Complete(savedUserId)
        }
    }

    sealed class OnboardingState {
        object Step1Gender : OnboardingState()
        object Step2Steadi : OnboardingState()
        data class Complete(val userId: Int) : OnboardingState()
    }
}
