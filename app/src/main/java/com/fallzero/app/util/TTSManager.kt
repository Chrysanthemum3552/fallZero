package com.fallzero.app.util

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 한국어 TTS 매니저 (Application-scope 싱글톤).
 *
 * 화면 전환마다 TextToSpeech engine init/shutdown 비용(~500ms × N) 제거를 위해 싱글톤.
 * 호출처는 `TTSManager.getInstance(context)`로 받음. 단일 native engine을 모든 Fragment가 공유.
 *
 * 행동 보존 (사용자 명시 — 끊김/카운트/정확도 100% 동일):
 *  - Fragment.onDestroyView에서 호출하던 shutdown()은 stop()으로 alias 처리.
 *    → 진행 중 발화 cut-off + pending callback 클리어 (기존 shutdown 동작 동일).
 *  - 실제 TextToSpeech.shutdown()은 호출 안 함 (Android process 종료 시 OS가 자동 회수).
 *
 * speak(text, onDone) — UtteranceProgressListener의 onDone 콜백으로 발화 완료를 정확히 감지.
 *   기존 polling 기반 waitForTtsFinish의 race condition(발화 시작 전 isSpeaking=false → 즉시 callback) 해결.
 * speak(text) — 콜백 없는 fire-and-forget. 카운트 음성, 짧은 hint 등 non-critical 호출에 사용.
 * speakSequence(...) — 여러 발화 순차 재생 후 마지막에 onDone 호출. 균형 10초 "십!" + "좋아요!"용.
 */
class TTSManager private constructor(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private var isReady = false
    private var isInitialized = false

    /** 발화 시작 전 호출된 speak를 init 후 처리 — 콜백 보존 */
    private val pendingQueue = mutableListOf<Pair<String, (() -> Unit)?>>()

    /** utteranceId → onDone callback 맵핑 (UtteranceProgressListener에서 lookup) */
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    /** 디버그용: utteranceId → text 맵 (onStart 시 어떤 텍스트인지 로그) */
    private val pendingTexts = ConcurrentHashMap<String, String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTSManager", "한국어 TTS를 지원하지 않습니다")
                return
            }
            isInitialized = true
            isReady = true
            tts.setSpeechRate(0.75f)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val text = pendingTexts.remove(utteranceId)
                    Log.d("TTSDebug", "▶ onStart  id=$utteranceId text=\"$text\"")
                }
                override fun onDone(utteranceId: String?) {
                    Log.d("TTSDebug", "✓ onDone   id=$utteranceId")
                    val cb = utteranceId?.let { callbacks.remove(it) } ?: return
                    // TTS 스레드 → 메인 스레드 마샬 (Fragment binding 안전)
                    mainHandler.post(cb)
                }
                @Deprecated("deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    Log.w("TTSDebug", "✗ onError  id=$utteranceId (deprecated)")
                    val cb = utteranceId?.let { callbacks.remove(it) } ?: return
                    mainHandler.post(cb)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w("TTSDebug", "✗ onError  id=$utteranceId code=$errorCode")
                    val cb = utteranceId?.let { callbacks.remove(it) } ?: return
                    mainHandler.post(cb)
                }
            })

            // init 전 pending 처리 — 콜백 그대로 전달
            pendingQueue.forEach { (text, cb) -> doSpeak(text, cb) }
            pendingQueue.clear()
        }
    }

    /** 콜백 없는 발화 (기존 호환). 카운트 음성, 짧은 hint 등에 사용. */
    fun speak(text: String) = speak(text, null)

    /** 콜백 있는 발화. onDone은 발화 완료(또는 에러) 시점에 메인 스레드에서 호출. */
    fun speak(text: String, onDone: (() -> Unit)?) {
        if (!isReady) {
            // init 전이면 큐잉 — 콜백 보존
            pendingQueue.add(text to onDone)
            return
        }
        doSpeak(text, onDone)
    }

    private fun doSpeak(text: String, onDone: (() -> Unit)?) {
        val id = UUID.randomUUID().toString()
        if (onDone != null) callbacks[id] = onDone
        pendingTexts[id] = text
        Log.d("TTSDebug", "→ speak    id=$id mode=FLUSH text=\"$text\" hasCb=${onDone != null}")
        val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) Log.w("TTSDebug", "✗ speak() returned $r for id=$id")
    }

    /** 여러 발화를 순차 재생 후 마지막에 onDone 호출. QUEUE_ADD 사용. */
    fun speakSequence(vararg texts: String, onDone: () -> Unit) {
        if (texts.isEmpty()) {
            mainHandler.post(onDone)
            return
        }
        if (!isReady) {
            // init 전이면 첫 발화에 onDone 묶고 나머지를 chained 처리
            // 단순화: 모든 발화를 큐잉, 마지막에 onDone
            for ((i, text) in texts.withIndex()) {
                if (i == texts.lastIndex) pendingQueue.add(text to onDone)
                else pendingQueue.add(text to null)
            }
            return
        }
        // 첫 발화는 FLUSH (이전 발화 cut), 나머지는 ADD
        for ((i, text) in texts.withIndex()) {
            val id = UUID.randomUUID().toString()
            if (i == texts.lastIndex) callbacks[id] = onDone
            pendingTexts[id] = text
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val modeName = if (i == 0) "FLUSH" else "ADD"
            Log.d("TTSDebug", "→ seq[$i]   id=$id mode=$modeName text=\"$text\"")
            tts.speak(text, mode, null, id)
        }
    }

    /** @deprecated speak(text, onDone) 사용 권장. polling 기반은 race condition. */
    @Deprecated("Use speak(text, onDone) callback API instead")
    fun isSpeaking(): Boolean = isInitialized && tts.isSpeaking

    fun stop() {
        Log.d("TTSDebug", "◼ stop()")
        callbacks.clear()
        pendingTexts.clear()
        tts.stop()
    }

    /** Fragment.onDestroyView에서 호출됨. 싱글톤으로 변경되며 실제 TTS 엔진은 종료하지 않음.
     *  진행 발화 cut-off + pending callback 클리어 → 기존 shutdown()의 사용자 관찰 동작 100% 보존. */
    fun shutdown() {
        Log.d("TTSDebug", "◼ shutdown() — singleton: alias for stop()")
        // dangling lambda 방지 — Fragment 파괴 후 onDone NPE 방지
        callbacks.clear()
        pendingTexts.clear()
        pendingQueue.clear()
        tts.stop()
    }

    companion object {
        @Volatile private var INSTANCE: TTSManager? = null

        /** Application-scope 싱글톤. 첫 호출 시 init (~500ms 비동기), 이후 호출 즉시 반환. */
        fun getInstance(context: Context): TTSManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TTSManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
