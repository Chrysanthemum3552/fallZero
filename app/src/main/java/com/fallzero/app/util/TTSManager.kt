package com.fallzero.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private var isInitialized = false
    private val pendingQueue = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTSManager", "한국어 TTS를 지원하지 않습니다")
            } else {
                isInitialized = true
                pendingQueue.forEach { speak(it) }
                pendingQueue.clear()
            }
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!isInitialized) {
            pendingQueue.add(text)
            return
        }
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, queueMode, null, null)
    }

    fun speakCount(count: Int) {
        speak("${count}회", flush = false)
    }

    /** 현재 음성이 재생 중인지 */
    fun isSpeaking(): Boolean = isInitialized && tts.isSpeaking

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
