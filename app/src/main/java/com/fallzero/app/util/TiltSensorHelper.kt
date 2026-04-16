package com.fallzero.app.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 폰 직립(수직 portrait) 감지용 헬퍼.
 *
 * 가속도 센서로 중력 벡터의 Y축 성분을 측정하여 직립도 판정.
 * - 폰을 portrait로 똑바로 세웠을 때: gravity = (0, +9.8, 0)
 * - tiltDeg = arccos(y / |g|) → 0°에 가까울수록 완벽한 직립
 *
 * Roll(좌우 기울기)는 무시 — 수직(forward/back pitch)만 검사.
 *
 * @param tiltOkThresholdDeg 허용 각도(기본 5°)
 */
class TiltSensorHelper(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 콜백: (tiltDeg, isWithinThreshold) */
    private var listener: ((Float, Boolean) -> Unit)? = null

    var tiltOkThresholdDeg: Float = 5f

    // 저역통과 필터로 손떨림 제거
    private val filtered = FloatArray(3)
    private var filterInitialized = false
    private val alpha = 0.2f

    fun startListening(onTilt: (tiltDeg: Float, isOk: Boolean) -> Unit) {
        listener = onTilt
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
        listener = null
        filterInitialized = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val v = event.values
        if (v.size < 3) return

        if (!filterInitialized) {
            filtered[0] = v[0]; filtered[1] = v[1]; filtered[2] = v[2]
            filterInitialized = true
        } else {
            filtered[0] += alpha * (v[0] - filtered[0])
            filtered[1] += alpha * (v[1] - filtered[1])
            filtered[2] += alpha * (v[2] - filtered[2])
        }

        val x = filtered[0]; val y = filtered[1]; val z = filtered[2]
        val mag = sqrt(x * x + y * y + z * z)
        if (mag < 1e-3f) return

        // 직립(portrait standing) 시 gravity의 Y 성분이 +g에 가까움
        // tiltFromVertical = arccos(y / |g|)
        val cos = (y / mag).coerceIn(-1f, 1f)
        val tiltDeg = Math.toDegrees(acos(cos).toDouble()).toFloat()
        val isOk = tiltDeg <= tiltOkThresholdDeg
        listener?.invoke(tiltDeg, isOk)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
