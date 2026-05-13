package com.fallzero.app.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 운동 진행도 표시 게이지 + 목표 ✓ 체크.
 *
 * 한 가지 컴포넌트가 수직/수평/양방향 모두 처리 — [ExerciseGuide.Bar.vertical] + [ExerciseGuide.Bar.fillDirection].
 *
 * 시각 디자인:
 *  - 신호등 색: 빨강(0~30%) → 주황(30~80%) → 초록(80~100%)
 *  - 목표점 ✓: 빈 outline → 도달 시 채워지며 1회 점프 (scale 1.0 → 1.4 → 1.0, 400ms)
 *  - 운동명 라벨 표시
 *
 * 사용: setGuide(Bar(...)) 호출 → 자동 invalidate. 모든 처리 내부.
 */
class GuideBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var guide: ExerciseGuide.Bar? = null
    private var lastReachedState = false   // 이전 도달 상태 — false→true 전이 시 점프 트리거
    private var checkScale = 1f            // 체크 마크 크기 (애니메이션 중)
    private var checkAnimator: ValueAnimator? = null
    // 사용자 명시 4번: bar 순간이동 방지 — EMA로 점진 보간 (매 setGuide 호출마다 target에 한 발씩 다가감)
    private var displayProgress: Float = 0f
    private val SMOOTH_ALPHA = 0.18f

    private val barPadding = 20f       // 막대 양쪽 여백
    // 사용자 명시: 지금보다 1.5배 굵게 (70 → 105)
    private val barThickness = 105f    // 막대 두께
    private val targetMarkSize = 90f   // 목표점 ✓ 크기
    private val labelTextSize = 38f

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val checkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = labelTextSize
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    fun setGuide(g: ExerciseGuide.Bar?) {
        guide = g
        if (g != null) {
            // 사용자 명시: EMA smoothing 제거 — 즉시 target 적용 (안내 시뮬레이션 0→100% 자연스럽게)
            displayProgress = g.progress
            val reachedNow = displayProgress >= 0.99f
            if (!lastReachedState && reachedNow) triggerJump()
            lastReachedState = reachedNow
        } else {
            lastReachedState = false
            displayProgress = 0f
        }
        invalidate()
    }

    private fun triggerJump() {
        checkAnimator?.cancel()
        checkAnimator = ValueAnimator.ofFloat(1f, 1.4f, 1f).apply {
            duration = 400L
            addUpdateListener {
                checkScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 신호등 색: 0~0.30 빨강 / 0.30~0.80 주황 / 0.80+ 초록 */
    private fun colorForProgress(p: Float): Int {
        return when {
            p >= 0.80f -> Color.parseColor("#00E676")  // 초록
            p >= 0.30f -> Color.parseColor("#FF9800")  // 주황
            else -> Color.parseColor("#F44336")        // 빨강
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = guide ?: return

        val w = width.toFloat()
        val h = height.toFloat()
        // 사용자 명시 4번: g.progress(target) 대신 displayProgress(EMA 보간된 값) 사용 — 부드러움
        val progress = displayProgress.coerceIn(0f, 1f)
        val fillColor = colorForProgress(progress)
        fillPaint.color = fillColor

        if (g.vertical) {
            // ── 수직 막대 — 화면 우측 ──
            val barRight = w - barPadding
            val barLeft = barRight - barThickness
            val labelHeight = labelTextSize + 14f
            val checkSpace = targetMarkSize * 1.4f + 20f
            // 채움 방향에 따라 막대/체크/라벨 위치 분기.
            //  UP: 위 = 체크+라벨 공간, 아래 = padding만
            //  DOWN: 위 = padding만, 아래 = 체크+라벨 공간
            val isDown = g.fillDirection == ExerciseGuide.FillDirection.DOWN
            val barTop = if (isDown) barPadding else barPadding + labelHeight + checkSpace
            val barBottom = if (isDown) h - barPadding - labelHeight - checkSpace else h - barPadding

            // 배경
            canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 12f, 12f, backgroundPaint)
            canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 12f, 12f, borderPaint)

            // 채움 — 방향에 따라 위→아래 또는 아래→위
            val totalBarH = barBottom - barTop
            val fillH = totalBarH * progress
            val fillRect = if (isDown)
                RectF(barLeft, barTop, barRight, barTop + fillH)
            else
                RectF(barLeft, barBottom - fillH, barRight, barBottom)
            canvas.drawRoundRect(fillRect, 12f, 12f, fillPaint)

            // 목표 ✓ — 채움 종점에 표시.
            //  UP: 막대 위쪽 끝 위에 (사용자가 그쪽까지 채워야 함)
            //  DOWN: 막대 아래쪽 끝 아래에
            val checkCx = (barLeft + barRight) / 2f
            val checkCy = if (isDown) barBottom + checkSpace / 2f else barTop - checkSpace / 2f
            drawCheck(canvas, checkCx, checkCy, progress >= 1f, fillColor)

            // 라벨
            val labelY = if (isDown)
                barBottom + checkSpace + labelTextSize + 12f
            else
                barTop - checkSpace - 12f
            canvas.drawText(g.label, checkCx, labelY, labelPaint)

        } else {
            // ── 수평 막대 — 화면 하단 ──
            val labelHeight = labelTextSize + 14f
            val barBottom = h - barPadding - labelHeight
            val barTop = barBottom - barThickness
            val checkSpaceH = targetMarkSize * 1.4f + 20f
            val barLeft = barPadding + checkSpaceH
            val barRight = w - barPadding - checkSpaceH

            // 배경
            canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 12f, 12f, backgroundPaint)
            canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 12f, 12f, borderPaint)

            // 채움
            val totalBarW = barRight - barLeft
            val centerX = (barLeft + barRight) / 2f
            val fillRect = when (g.fillDirection) {
                ExerciseGuide.FillDirection.LEFT ->
                    RectF(barRight - totalBarW * progress, barTop, barRight, barBottom)
                ExerciseGuide.FillDirection.RIGHT ->
                    RectF(barLeft, barTop, barLeft + totalBarW * progress, barBottom)
                ExerciseGuide.FillDirection.FROM_CENTER_LEFT ->
                    RectF(centerX - (totalBarW / 2f) * progress, barTop, centerX, barBottom)
                ExerciseGuide.FillDirection.FROM_CENTER_RIGHT ->
                    RectF(centerX, barTop, centerX + (totalBarW / 2f) * progress, barBottom)
                else ->
                    RectF(barLeft, barTop, barLeft + totalBarW * progress, barBottom)
            }
            canvas.drawRoundRect(fillRect, 12f, 12f, fillPaint)

            // 목표 ✓ — 채움 방향 끝에 표시
            val checkCy = (barTop + barBottom) / 2f
            val checkCx = when (g.fillDirection) {
                ExerciseGuide.FillDirection.LEFT -> barLeft - checkSpaceH / 2f
                ExerciseGuide.FillDirection.RIGHT -> barRight + checkSpaceH / 2f
                ExerciseGuide.FillDirection.FROM_CENTER_LEFT -> barLeft - checkSpaceH / 2f
                ExerciseGuide.FillDirection.FROM_CENTER_RIGHT -> barRight + checkSpaceH / 2f
                else -> barRight + checkSpaceH / 2f
            }
            drawCheck(canvas, checkCx, checkCy, progress >= 1f, fillColor)

            // 라벨 — 막대 아래
            canvas.drawText(g.label, w / 2f, barBottom + labelTextSize + 12f, labelPaint)
        }
    }

    /** ✓ 체크 마크 그리기 — 미달성: 빈 outline, 달성: 색 채움 + scale 애니메이션 */
    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, reached: Boolean, color: Int) {
        val size = targetMarkSize * checkScale
        val half = size / 2f

        // 원 (배경)
        if (reached) {
            checkFillPaint.color = color
            canvas.drawCircle(cx, cy, half, checkFillPaint)
        } else {
            checkPaint.color = Color.parseColor("#FFFFFF")
            checkPaint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, half, checkPaint)
        }

        // ✓ 마크 path
        val checkPath = Path().apply {
            moveTo(cx - half * 0.4f, cy + half * 0.05f)
            lineTo(cx - half * 0.05f, cy + half * 0.4f)
            lineTo(cx + half * 0.45f, cy - half * 0.3f)
        }
        checkPaint.style = Paint.Style.STROKE
        checkPaint.color = if (reached) Color.WHITE else Color.parseColor("#FFFFFF")
        canvas.drawPath(checkPath, checkPaint)
    }
}
