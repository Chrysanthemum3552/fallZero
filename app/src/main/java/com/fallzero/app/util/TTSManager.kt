package com.fallzero.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private var isReady = false
    private var isInitialized = false
    private val pendingQueue = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTSManager", "한국어 TTS를 지원하지 않습니다")
            } else {
                isInitialized = true
                isReady = true
                tts.setSpeechRate(0.75f)
                pendingQueue.forEach { speak(it) }
                pendingQueue.clear()
            }
        }
    }

    fun speak(text: String) {
       if(!isReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }
    fun speakAdd(text: String){
        if(!isReady) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    fun speakCount(count: Int) {
        speak("${count}회")
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
