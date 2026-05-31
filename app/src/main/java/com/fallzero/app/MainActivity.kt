package com.fallzero.app

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        // ActionBar 타이틀 — colorPrimary가 노란색이라 Material 기본 흰 글씨로는 안 보임.
        // SpannableString으로 검정(=on_primary) 색상 강제. "낙상제로" 표시.
        supportActionBar?.title = SpannableString("낙상제로").apply {
            setSpan(ForegroundColorSpan(Color.BLACK), 0, length, 0)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 사용자 명시: 앱 실행 중 화면이 timeout으로 꺼지지 않게.
        // FLAG_KEEP_SCREEN_ON은 Window 단위라 앱이 foreground일 때만 적용됨 (background로 가면 자동 해제).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 키오스크 빌드: Status bar + Navigation bar 영구 숨김 (Immersive Sticky)
        if (com.fallzero.app.BuildConfig.IS_KIOSK) applyKioskImmersive()

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 다이얼로그 등 잠시 포커스 벗어났다 돌아올 때 다시 immersive 강제
        if (hasFocus && com.fallzero.app.BuildConfig.IS_KIOSK) applyKioskImmersive()
    }

    private fun applyKioskImmersive() {
        // 상단 노란 ActionBar(앱 타이틀 바) 제거 — 화면이 위까지 꽉 차고 콘텐츠 가림 방지
        supportActionBar?.hide()
        // Status bar + Navigation bar 영구 숨김 (swipe로 일시 노출 가능)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
