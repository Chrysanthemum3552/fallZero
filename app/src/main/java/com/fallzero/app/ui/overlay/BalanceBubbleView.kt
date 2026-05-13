package com.fallzero.app.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 균형 운동·검사용 거대 원형 링 — 사용자 명시.
 *
 * 시각 디자인:
 *  - 거대한 원형 링 (외곽 두꺼운 stroke). 시계방향(12시→3시→6시→9시) 채움.
 *  - 가운데 큰 노란 숫자: 현재까지 안전영역 유지한 경과 초 (정수 표시).
 *  - 링 색상 (신호등):
 *      안정 (swayRatio < 0.5): 초록 #00E676
 *      보통 (0.5 ≤ swayRatio < 1.0): 노랑 #FFEB3B
 *      흔들림/자세 틀림 (swayRatio ≥ 1.0 또는 poseValid=false): 빨강 #F44336
 *  - 라벨은 링 상단 바깥쪽.
 *
 *  이전 BalanceBubbleView의 작은 버블/십자선/안전영역 표시는 제거 (사용자 명시: 직관적이지 않음).
 *  하단 검정 영역 축소로 카메라 영역 확보는 layout 측 작업.
 */
class BalanceBubbleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var swayRatio: Float = 0f
    private var holdProgress: Float = 0f  // 0~1
    private var label: String = ""
    private var elapsedSec: Float = 0f
    private var targetSec: Float = 10f
    private var poseValid: Boolean = true

    // 배경 링 (회색 트랙)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }
    // 진행 호 (시계방향, 색은 신호등)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 28f
        strokeCap = Paint.Cap.ROUND
    }
    // 가운데 숫자 (큰 노란 글씨)
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF00")
        textSize = 220f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(8f, 3f, 3f, Color.BLACK)
    }
    // 라벨 (상단)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = 44f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }
    // "초" 단위 표시
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE066")
        textSize = 56f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    fun setGuide(g: ExerciseGuide.Bubble?) {
        if (g == null) {
            visibility = INVISIBLE
            return
        }
        visibility = VISIBLE
        swayRatio = g.swayRatio.coerceAtLeast(0f)
        holdProgress = g.holdProgress.coerceIn(0f, 1f)
        label = g.label
        elapsedSec = g.elapsedSec.coerceAtLeast(0f)
        targetSec = g.targetSec.coerceAtLeast(1f)
        poseValid = g.poseValid
        invalidate()
    }

    /** 신호등 색: 자세 valid + swayRatio 기준. */
    private fun ringColor(): Int {
        if (!poseValid) return Color.parseColor("#F44336")  // 자세 틀림 → 빨강
        return when {
            swayRatio < 0.5f -> Color.parseColor("#00E676")  // 안정 → 초록
            swayRatio < 1.0f -> Color.parseColor("#FFEB3B")  // 보통 → 노랑
            else -> Color.parseColor("#F44336")              // 흔들림 큼 → 빨강
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f + 24f  // 상단 라벨 공간
        val outerRadius = min(width, height) / 2f - 40f
        if (outerRadius <= 0f) return

        // 배경 트랙
        val oval = RectF(
            cx - outerRadius, cy - outerRadius,
            cx + outerRadius, cy + outerRadius
        )
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        // 진행 호 — 12시(=-90°)부터 시계방향
        if (holdProgress > 0f) {
            progressPaint.color = ringColor()
            canvas.drawArc(oval, -90f, 360f * holdProgress, false, progressPaint)
        }

        // 가운데 큰 숫자 — 경과 초 (정수)
        val secText = elapsedSec.toInt().toString()
        // textPaint baseline 보정
        val fm = numberPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(secText, cx, textY, numberPaint)

        // "초" 단위 — 숫자 아래
        canvas.drawText("초", cx, cy + outerRadius * 0.55f, unitPaint)

        // 라벨 — 링 상단
        canvas.drawText(label, cx, cy - outerRadius - 16f, labelPaint)
    }
}
