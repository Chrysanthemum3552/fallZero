package com.fallzero.app.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings

/**
 * TV 화면 미러링 헬퍼 (Option B — 폰 화면 그대로 TV에 표시)
 *
 * 작동 방식:
 *   폰의 시스템 "화면 공유/Cast" 기능을 앱 내 버튼으로 바로 열어줍니다.
 *   사용자가 TV를 선택하면 Android 기본 미러링이 시작되며
 *   폰 화면 전체가 그대로 TV에 표시됩니다.
 *
 * 지원:
 *   - Miracast 지원 스마트 TV (삼성, LG 등)
 *   - Chromecast 동글 또는 내장 Chromecast TV
 *   - Android TV (무선 디스플레이)
 *
 * 사용 방법:
 *   HomeFragment의 "TV 연결" 버튼 또는 운동 화면의 캐스트 버튼에서 호출
 */
object CastHelper {

    /**
     * 시스템 화면 공유 패널을 엽니다.
     * 연결 가능한 TV 목록이 시스템 다이얼로그로 표시됩니다.
     */
    fun openScreenCast(activity: Activity) {
        // 1순위: Android 기본 Cast 설정 화면
        val castIntent = Intent("android.settings.CAST_SETTINGS")
        try {
            activity.startActivity(castIntent)
            return
        } catch (_: ActivityNotFoundException) {}

        // 2순위: 무선 디스플레이 설정 (구형 기기)
        val wirelessIntent = Intent("android.settings.WIFI_DISPLAY_SETTINGS")
        try {
            activity.startActivity(wirelessIntent)
            return
        } catch (_: ActivityNotFoundException) {}

        // 3순위: 일반 디스플레이 설정으로 폴백
        activity.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
    }
}
