package com.fallzero.app.pose

/**
 * 실시간 메트릭 스무딩 (지수이동평균, EMA)
 * MediaPipe 랜드마크의 프레임 간 떨림을 억제하여
 * 카운트 점프와 오발동을 방지함.
 *
 * alpha가 클수록 최신 값에 가중치가 높아짐 (반응 빠름, 노이즈에 취약)
 * alpha가 작을수록 과거 값에 가중치가 높아짐 (반응 느림, 노이즈에 강함)
 *
 * 운동 동작은 느리므로 alpha=0.3 이 적절 (약 3프레임 지연)
 */
class MetricSmoother(private val alpha: Float = 0.3f) {

    private var smoothedValue: Float? = null

    /** 새 raw 값을 입력하고 스무딩된 값을 반환 */
    fun smooth(rawValue: Float): Float {
        val prev = smoothedValue
        val result = if (prev == null) {
            rawValue
        } else {
            prev + alpha * (rawValue - prev)
        }
        smoothedValue = result
        return result
    }

    /** 상태 초기화 (운동 시작 시 호출) */
    fun reset() {
        smoothedValue = null
    }
}
