package com.fallzero.app.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 원형 버블 수평계 — 핸드폰이 직립(portrait, 수직)인지 시각적으로 표시.
 *
 * 큰 동심원 = 목표 영역 (5° 허용). 작은 버블이 가운데에 들어오면 OK.
 * 버블 위치는 tiltDeg(0~90°)에 비례하여 가운데에서 바깥으로 이동.
 *
 * - tiltDeg ≤ thresholdDeg: 초록색, 가운데 정렬
 * - tiltDeg >  thresholdDeg: 주황색, 바깥쪽으로 이동
 */
class PhoneLevelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var tiltDeg: Float = 90f
    private var isOk: Boolean = false
    var thresholdDeg: Float = 5f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        strokeWidth = 3f
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }

    fun setTilt(tiltDeg: Float, isOk: Boolean) {
        this.tiltDeg = tiltDeg
        this.isOk = isOk
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = min(width, height) / 2f - 12f
        val targetRadius = outerRadius * 0.22f
        val bubbleRadius = outerRadius * 0.18f

        // 외곽 원
        canvas.drawCircle(cx, cy, outerRadius, ringPaint)

        // 십자선
        canvas.drawLine(cx - outerRadius, cy, cx + outerRadius, cy, crossPaint)
        canvas.drawLine(cx, cy - outerRadius, cx, cy + outerRadius, crossPaint)

        // 목표 원 (5° 영역)
        targetRingPaint.color = if (isOk) Color.parseColor("#4CAF50") else Color.parseColor("#FFB300")
        canvas.drawCircle(cx, cy, targetRadius + 8f, targetRingPaint)

        // 버블 위치: tilt 0~90°를 반지름에 매핑
        val ratio = (tiltDeg / 90f).coerceIn(0f, 1f)
        // 직립이면 가운데, 기울어질수록 아래로 이동
        val bubbleY = cy + ratio * (outerRadius - bubbleRadius - 8f)
        val bubbleX = cx
        bubblePaint.color = if (isOk) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800")
        canvas.drawCircle(bubbleX, bubbleY, bubbleRadius, bubblePaint)
        canvas.drawCircle(bubbleX, bubbleY, bubbleRadius, bubbleStroke)
    }
}
