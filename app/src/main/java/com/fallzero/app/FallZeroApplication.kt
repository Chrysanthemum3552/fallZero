package com.fallzero.app

import android.app.Application
import com.fallzero.app.util.NotificationHelper

class FallZeroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
    }
}
