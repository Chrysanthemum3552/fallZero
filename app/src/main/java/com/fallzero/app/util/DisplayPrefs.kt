package com.fallzero.app.util

import android.content.Context

/**
 * 화면 표시 옵션 — 사용자가 설정에서 토글하는 두 가지 시각 요소.
 *
 * - showGuide: 운동·검사 중 "어디까지 해야 할지" 게이지/버블 가이드 (default ON — 노년층 직관성)
 * - showSkeleton: 33개 관절 점 + 연결선 (default OFF — 노년층 화면 단순화)
 *
 * SharedPreferences "fallzero_prefs" 키:
 *  - "show_exercise_guide" (boolean, default true)
 *  - "show_pose_skeleton" (boolean, default false)
 */
object DisplayPrefs {
    private const val PREFS_NAME = "fallzero_prefs"
    private const val KEY_SHOW_GUIDE = "show_exercise_guide"
    private const val KEY_SHOW_SKELETON = "show_pose_skeleton"

    fun showGuide(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_GUIDE, true)  // default ON

    fun setShowGuide(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_GUIDE, value).apply()
    }

    fun showSkeleton(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SKELETON, false)  // default OFF

    fun setShowSkeleton(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SKELETON, value).apply()
    }
}
