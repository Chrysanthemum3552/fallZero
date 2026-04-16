package com.fallzero.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fallzero.app.util.NotificationHelper

/**
 * WorkManager Worker — 하루 3회 운동 알림
 * 스케줄: 오전 9시, 오후 1시, 오후 8시
 * MainActivity.scheduleReminders() 에서 등록
 */
class ExerciseReminderWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val slot = inputData.getString(KEY_SLOT) ?: "morning"
        val (title, body, notifId) = when (slot) {
            "morning" -> Triple(
                "굿모닝! 오늘 운동 시작해요 🌅",
                "10분 낙상 예방 운동으로 하루를 시작하세요",
                NotificationHelper.NOTIFICATION_ID_MORNING
            )
            "afternoon" -> Triple(
                "오후 운동 시간이에요 ☀️",
                "잠깐 스트레칭하고 균형 운동을 해보세요",
                NotificationHelper.NOTIFICATION_ID_AFTERNOON
            )
            else -> Triple(
                "저녁 운동으로 하루를 마무리해요 🌙",
                "오늘 운동 목표를 달성하셨나요?",
                NotificationHelper.NOTIFICATION_ID_EVENING
            )
        }

        NotificationHelper.sendReminderNotification(applicationContext, notifId, title, body)
        return Result.success()
    }

    companion object {
        const val KEY_SLOT = "reminder_slot"
    }
}
