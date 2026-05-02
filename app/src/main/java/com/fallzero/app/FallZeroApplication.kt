package com.fallzero.app

import android.app.Application
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.util.NotificationHelper
import com.fallzero.app.util.TTSManager

class FallZeroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)

        // 워밍업 — 앱 시작 시 무거운 객체 미리 init.
        // 모두 Application context 사용으로 메모리 leak 없음.
        // 1) TTS engine ~500ms init: 첫 화면 진입 시 발화 지연 제거
        // 2) Room DB ~150ms init: 첫 쿼리 시 cold start lag 제거
        TTSManager.getInstance(this)
        FallZeroDatabase.getInstance(this)
    }
}
