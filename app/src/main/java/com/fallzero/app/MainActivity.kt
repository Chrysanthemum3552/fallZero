package com.fallzero.app

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.ActivityMainBinding
import com.fallzero.app.worker.ExerciseReminderWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 앱 시작 시 SessionFlow 초기화 (이전 세션 잔여 상태 제거)
        SessionFlow.reset()

        // 운동 알림 스케줄 등록 (앱 최초 실행 시)
        scheduleReminders()

        // 온보딩 완료 여부에 따라 시작 목적지 변경
        val prefs = getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val isOnboardingComplete = prefs.getBoolean("onboarding_complete", false)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        if (isOnboardingComplete) {
            // 온보딩 건너뛰고 홈으로
            val graph = navController.navInflater.inflate(R.navigation.nav_graph)
            graph.setStartDestination(R.id.homeFragment)
            navController.setGraph(graph, null)
        }
        // 온보딩 미완료면 nav_graph 기본 startDestination(onboardingFragment) 그대로 진행
    }

    /**
     * WorkManager로 하루 3회 알림 등록
     * 오전 9시 / 오후 1시 / 오후 8시
     * 설정 화면에서 끄고 싶을 경우 WorkManager.cancelAllWork() 호출
     */
    private fun scheduleReminders() {
        val slots = listOf(
            "morning" to 9,
            "afternoon" to 13,
            "evening" to 20
        )
        slots.forEach { (slot, hour) ->
            val delay = calcInitialDelayMs(hour)
            val request = PeriodicWorkRequestBuilder<ExerciseReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putString(ExerciseReminderWorker.KEY_SLOT, slot).build())
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "reminder_$slot",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    private fun calcInitialDelayMs(targetHour: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
