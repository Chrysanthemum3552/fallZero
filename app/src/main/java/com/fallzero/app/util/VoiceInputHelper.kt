package com.fallzero.app.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 한국어 음성 인식 helper. 온보딩 5개 Fragment(성별/나이/Q1/Q2/Q3)에서 공용으로 사용.
 *
 * 사용 패턴:
 *  val helper = VoiceInputHelper(requireContext())
 *  helper.start(onResult = { text -> handleAnswer(text) })
 *  // Fragment.onDestroyView 등에서:
 *  helper.destroy()
 *
 * 주의: 마이크 권한(RECORD_AUDIO) 체크는 호출자가 별도로 처리해야 함.
 * Fragment의 lifecycle에 맞춰 onDestroyView에서 destroy() 호출 필수.
 */
class VoiceInputHelper(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile private var isListening = false
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((Int) -> Unit)? = null
    private var onReady: (() -> Unit)? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            // 마이크가 실제로 켜진 시점 — UI에서 "듣고 있어요" 표시 활성
            onReady?.invoke()
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false }
        override fun onError(error: Int) {
            isListening = false
            onError?.invoke(error)
        }
        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) onResult?.invoke(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * 음성 인식 시작. 이미 듣고 있으면 무시.
     * @param onResult 인식된 텍스트 콜백 (raw 한국어 문자열)
     * @param onError 인식 실패 시 콜백 (SpeechRecognizer error code)
     * @param onReady 마이크가 실제로 켜진 시점 콜백 (UI "듣고 있어요" 표시용)
     */
    fun start(
        onResult: (String) -> Unit,
        onError: (Int) -> Unit = {},
        onReady: (() -> Unit)? = null
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        if (isListening) return

        this.onResult = onResult
        this.onError = onError
        this.onReady = onReady

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stop() {
        speechRecognizer?.stopListening()
        isListening = false
        onResult = null
        onError = null
        onReady = null
    }

    fun destroy() {
        stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun isListening(): Boolean = isListening
}
