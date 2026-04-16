package com.fallzero.app.util

import android.os.CountDownTimer

class TimerHelper {

    private var countDownTimer: CountDownTimer? = null

    fun startCountDown(
        durationMs: Long,
        onTick: (remainingMs: Long) -> Unit,
        onFinish: () -> Unit
    ) {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(durationMs, 100) {
            override fun onTick(millisUntilFinished: Long) = onTick(millisUntilFinished)
            override fun onFinish() = onFinish()
        }.start()
    }

    fun startCountUp(
        intervalMs: Long = 1000L,
        onTick: (elapsedSeconds: Int) -> Unit
    ) {
        var elapsed = 0
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(Long.MAX_VALUE, intervalMs) {
            override fun onTick(millisUntilFinished: Long) {
                elapsed++
                onTick(elapsed)
            }
            override fun onFinish() {}
        }.start()
    }

    fun stop() {
        countDownTimer?.cancel()
        countDownTimer = null
    }
}
