package com.fallzero.app.util

import android.content.Context

/**
 * 카메라 전후면 설정을 SharedPreferences에 영구 저장.
 * 사용자가 한 번 전환하면 다음 운동/검사에도 같은 카메라 유지.
 */
object CameraFacingPref {
    private const val PREF_NAME = "fallzero_prefs"
    private const val KEY_FRONT = "camera_facing_front"

    fun isFrontCamera(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FRONT, false)
    }

    fun setFrontCamera(context: Context, isFront: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FRONT, isFront).apply()
    }
}
