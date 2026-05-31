package com.fallzero.app.util

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import com.fallzero.app.BuildConfig

/**
 * 카메라 "장치 선택"만 담당하는 헬퍼.
 *
 *  - 앱 모드(standard, IS_KIOSK=false): 기존과 동일 — 전면/후면(FRONT/BACK).
 *  - 키오스크 모드(kiosk, IS_KIOSK=true): USB 웹캠은 facing이 EXTERNAL이라
 *    DEFAULT_FRONT/BACK 선택자로는 "No available camera"가 난다.
 *    → EXTERNAL 카메라를 우선 선택하고, 없으면 사용 가능한 첫 카메라로 폴백.
 *
 * ⚠ 이 파일은 "어떤 카메라 장치에 바인딩할지"만 결정한다.
 *    MediaPipe 좌표·감지·카운트 로직과는 전혀 무관하다.
 */
object KioskCameraSelector {

    @OptIn(ExperimentalCamera2Interop::class)
    fun select(isFrontCamera: Boolean): CameraSelector {
        if (!BuildConfig.IS_KIOSK) {
            return if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
        }
        // 키오스크: EXTERNAL(USB 웹캠) 우선
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                val external = infos.filter { info ->
                    try {
                        Camera2CameraInfo.from(info)
                            .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_EXTERNAL
                    } catch (e: Exception) {
                        false
                    }
                }
                when {
                    external.isNotEmpty() -> external
                    infos.isNotEmpty() -> listOf(infos.first())
                    else -> infos
                }
            }
            .build()
    }
}
