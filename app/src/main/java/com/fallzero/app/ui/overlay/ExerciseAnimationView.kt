package com.fallzero.app.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 운동 가이드 애니메이션을 Canvas로 직접 그리는 커스텀 뷰.
 * Lottie 파일 없이 각 운동의 동작을 스틱맨 애니메이션으로 보여줌.
 */
class ExerciseAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var exerciseId: Int = 0
    private var progress: Float = 0f  // 0..1 animation progress

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.FILL
    }

    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val chairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#795548")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.FILL_AND_STROKE
    }

    private var animator: ValueAnimator? = null

    fun setExercise(id: Int) {
        exerciseId = id
        startAnimation()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val scale = minOf(width, height) / 400f

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)

        // 바닥선
        canvas.drawLine(-150f, 140f, 150f, 140f, floorPaint)

        when (exerciseId) {
            1 -> drawKneeExtension(canvas)
            2 -> drawHipAbduction(canvas)
            3 -> drawKneeFlexion(canvas)
            4 -> drawCalfRaise(canvas)
            5 -> drawToeRaise(canvas)
            6 -> drawKneeBend(canvas)
            7 -> drawChairStand(canvas)
            8 -> drawBalance(canvas)
        }

        canvas.restore()
    }

    // 1. 대퇴사두근 강화 (의자에 앉아 다리 펴기)
    private fun drawKneeExtension(canvas: Canvas) {
        drawChair(canvas, -20f, 40f)
        val legAngle = -90f + progress * 70f  // -90(수직) → -20(거의 수평)

        // 몸통 (앉은 자세)
        canvas.drawCircle(-20f, -60f, 20f, headPaint)  // 머리
        canvas.drawLine(-20f, -40f, -20f, 30f, bodyPaint)  // 몸통
        // 팔
        canvas.drawLine(-20f, -20f, -50f, 10f, bodyPaint)
        canvas.drawLine(-20f, -20f, 10f, 10f, bodyPaint)
        // 허벅지 (수평)
        canvas.drawLine(-20f, 30f, 40f, 30f, bodyPaint)
        // 무릎 아래 (애니메이션)
        val kneeX = 40f
        val kneeY = 30f
        val footX = kneeX + 60f * cos(Math.toRadians(legAngle.toDouble())).toFloat()
        val footY = kneeY + 60f * sin(Math.toRadians(legAngle.toDouble())).toFloat()
        canvas.drawLine(kneeX, kneeY, footX, footY, bodyPaint)

        drawMotionArrow(canvas, footX, footY - 20f, isUp = progress > 0.5f)
    }

    // 2. 고관절 외전 (서서 다리 옆으로 들기)
    private fun drawHipAbduction(canvas: Canvas) {
        val legSpread = progress * 45f  // 0 → 45도

        // 머리 + 몸통
        canvas.drawCircle(0f, -100f, 20f, headPaint)
        canvas.drawLine(0f, -80f, 0f, 20f, bodyPaint)
        // 팔 (의자 잡는 모습)
        canvas.drawLine(0f, -40f, -40f, -20f, bodyPaint)
        canvas.drawLine(0f, -40f, 40f, -20f, bodyPaint)
        // 왼다리 (고정)
        canvas.drawLine(0f, 20f, 0f, 80f, bodyPaint)
        canvas.drawLine(0f, 80f, 0f, 140f, bodyPaint)
        // 오른다리 (옆으로 들기)
        val hipX = 0f
        val hipY = 20f
        val kneeX = hipX + 60f * sin(Math.toRadians(legSpread.toDouble())).toFloat()
        val kneeY = hipY + 60f * cos(Math.toRadians(legSpread.toDouble())).toFloat()
        val footX = kneeX + 60f * sin(Math.toRadians(legSpread.toDouble())).toFloat()
        val footY = kneeY + 60f * cos(Math.toRadians(legSpread.toDouble())).toFloat()
        canvas.drawLine(hipX, hipY, kneeX, kneeY, bodyPaint)
        canvas.drawLine(kneeX, kneeY, footX, footY, bodyPaint)

        drawMotionArrow(canvas, footX + 10f, footY, isUp = false)
    }

    // 3. 슬굴곡근 강화 (서서 무릎 뒤로 굽히기)
    private fun drawKneeFlexion(canvas: Canvas) {
        val bendAngle = progress * 80f  // 0 → 80도

        // 머리 + 몸통
        canvas.drawCircle(0f, -100f, 20f, headPaint)
        canvas.drawLine(0f, -80f, 0f, 20f, bodyPaint)
        canvas.drawLine(0f, -40f, -30f, -10f, bodyPaint)
        canvas.drawLine(0f, -40f, 30f, -10f, bodyPaint)
        // 왼다리 (고정)
        canvas.drawLine(0f, 20f, -10f, 80f, bodyPaint)
        canvas.drawLine(-10f, 80f, -10f, 140f, bodyPaint)
        // 오른다리 (뒤로 굽히기: 무릎에서 발이 뒤쪽 위로 올라감)
        canvas.drawLine(0f, 20f, 10f, 80f, bodyPaint)
        val kneeX = 10f
        val kneeY = 80f
        // 발이 뒤쪽(양의 X)으로, 위쪽(음의 Y)으로 이동
        val shinX = kneeX + 60f * sin(Math.toRadians(bendAngle.toDouble())).toFloat()
        val shinY = kneeY - 60f * cos(Math.toRadians(bendAngle.toDouble())).toFloat()
        canvas.drawLine(kneeX, kneeY, shinX, shinY, bodyPaint)

        drawMotionArrow(canvas, shinX, shinY - 10f, isUp = progress > 0.5f)
    }

    // 4. 발뒤꿈치 들기
    private fun drawCalfRaise(canvas: Canvas) {
        val raiseY = -progress * 30f

        canvas.drawCircle(0f, -100f + raiseY, 20f, headPaint)
        canvas.drawLine(0f, -80f + raiseY, 0f, 20f + raiseY, bodyPaint)
        canvas.drawLine(0f, -40f + raiseY, -30f, -10f + raiseY, bodyPaint)
        canvas.drawLine(0f, -40f + raiseY, 30f, -10f + raiseY, bodyPaint)
        // 다리
        canvas.drawLine(-15f, 20f + raiseY, -15f, 80f + raiseY, bodyPaint)
        canvas.drawLine(15f, 20f + raiseY, 15f, 80f + raiseY, bodyPaint)
        // 발 (뒤꿈치가 올라감)
        canvas.drawLine(-15f, 80f + raiseY, -15f, 140f, bodyPaint)
        canvas.drawLine(15f, 80f + raiseY, 15f, 140f, bodyPaint)
        // 발바닥
        canvas.drawLine(-25f, 140f, -5f, 140f, bodyPaint)
        canvas.drawLine(5f, 140f, 25f, 140f, bodyPaint)

        drawMotionArrow(canvas, 40f, -50f + raiseY, isUp = true)
    }

    // 5. 발끝 들기
    private fun drawToeRaise(canvas: Canvas) {
        val toeAngle = progress * 30f

        canvas.drawCircle(0f, -100f, 20f, headPaint)
        canvas.drawLine(0f, -80f, 0f, 20f, bodyPaint)
        canvas.drawLine(0f, -40f, -30f, -10f, bodyPaint)
        canvas.drawLine(0f, -40f, 30f, -10f, bodyPaint)
        canvas.drawLine(-15f, 20f, -15f, 120f, bodyPaint)
        canvas.drawLine(15f, 20f, 15f, 120f, bodyPaint)
        // 발 (발끝이 올라감)
        val toeUpY = -sin(Math.toRadians(toeAngle.toDouble())).toFloat() * 20f
        canvas.drawLine(-15f, 120f, -35f, 140f + toeUpY, bodyPaint)
        canvas.drawLine(15f, 120f, 35f, 140f + toeUpY, bodyPaint)
        // 뒤꿈치 (고정)
        canvas.drawLine(-15f, 120f, -15f, 140f, bodyPaint)
        canvas.drawLine(15f, 120f, 15f, 140f, bodyPaint)

        drawMotionArrow(canvas, -45f, 130f + toeUpY, isUp = true)
    }

    // 6. 무릎 굽히기 (미니 스쿼트)
    private fun drawKneeBend(canvas: Canvas) {
        val bendDrop = progress * 40f
        val kneeForward = progress * 20f

        canvas.drawCircle(0f, -100f + bendDrop, 20f, headPaint)
        canvas.drawLine(0f, -80f + bendDrop, 0f, 20f + bendDrop * 0.5f, bodyPaint)
        canvas.drawLine(0f, -40f + bendDrop, -30f, -10f + bendDrop, bodyPaint)
        canvas.drawLine(0f, -40f + bendDrop, 30f, -10f + bendDrop, bodyPaint)
        // 다리 (무릎 굽힘)
        val hipY = 20f + bendDrop * 0.5f
        canvas.drawLine(-15f, hipY, -15f + kneeForward, 80f + bendDrop * 0.3f, bodyPaint)
        canvas.drawLine(-15f + kneeForward, 80f + bendDrop * 0.3f, -15f, 140f, bodyPaint)
        canvas.drawLine(15f, hipY, 15f + kneeForward, 80f + bendDrop * 0.3f, bodyPaint)
        canvas.drawLine(15f + kneeForward, 80f + bendDrop * 0.3f, 15f, 140f, bodyPaint)

        drawMotionArrow(canvas, 50f, -50f + bendDrop, isUp = progress < 0.5f)
    }

    // 7. 앉았다 일어서기
    private fun drawChairStand(canvas: Canvas) {
        drawChair(canvas, -30f, 50f)
        // progress 0 = 앉음, 1 = 서있음
        val standProgress = progress
        val bodyTilt = (1f - standProgress) * 30f  // 앉으면 몸이 뒤로
        val hipY = 30f - standProgress * 60f
        val headY = hipY - 60f

        canvas.drawCircle(-10f, headY, 20f, headPaint)
        canvas.drawLine(-10f, headY + 20f, -10f, hipY, bodyPaint)
        // 팔 교차
        canvas.drawLine(-10f, hipY - 30f, -30f, hipY - 20f, bodyPaint)
        canvas.drawLine(-10f, hipY - 30f, 10f, hipY - 20f, bodyPaint)
        // 다리
        if (standProgress < 0.5f) {
            // 앉은 상태: 허벅지 수평
            canvas.drawLine(-10f, hipY, 30f, hipY, bodyPaint)
            canvas.drawLine(30f, hipY, 30f, 140f, bodyPaint)
        } else {
            // 서는 중
            val kneeY = hipY + (140f - hipY) * 0.5f
            canvas.drawLine(-10f, hipY, -10f, kneeY, bodyPaint)
            canvas.drawLine(-10f, kneeY, -10f, 140f, bodyPaint)
        }

        drawMotionArrow(canvas, 60f, headY, isUp = true)
    }

    // 8. 균형 훈련 (탠덤 서기)
    private fun drawBalance(canvas: Canvas) {
        val sway = sin(progress * Math.PI * 2).toFloat() * 8f

        canvas.drawCircle(sway, -100f, 20f, headPaint)
        canvas.drawLine(sway, -80f, sway * 0.5f, 20f, bodyPaint)
        canvas.drawLine(sway, -40f, sway - 30f, -10f, bodyPaint)
        canvas.drawLine(sway, -40f, sway + 30f, -10f, bodyPaint)
        // 다리 (탠덤: 한 발 앞에)
        canvas.drawLine(sway * 0.5f, 20f, -10f + sway * 0.3f, 80f, bodyPaint)
        canvas.drawLine(-10f + sway * 0.3f, 80f, -20f, 140f, bodyPaint)
        canvas.drawLine(sway * 0.5f, 20f, 10f + sway * 0.3f, 80f, bodyPaint)
        canvas.drawLine(10f + sway * 0.3f, 80f, 10f, 140f, bodyPaint)

        // 균형 표시 화살표
        canvas.drawLine(-40f, -110f, 40f, -110f, arrowPaint)
        canvas.drawLine(-40f, -115f, -40f, -105f, arrowPaint)
        canvas.drawLine(40f, -115f, 40f, -105f, arrowPaint)
    }

    private fun drawChair(canvas: Canvas, x: Float, seatY: Float) {
        // 의자 좌석
        canvas.drawLine(x - 30f, seatY, x + 40f, seatY, chairPaint)
        // 등받이
        canvas.drawLine(x - 30f, seatY, x - 30f, seatY - 60f, chairPaint)
        // 다리
        canvas.drawLine(x - 25f, seatY, x - 25f, 140f, chairPaint)
        canvas.drawLine(x + 35f, seatY, x + 35f, 140f, chairPaint)
    }

    private fun drawMotionArrow(canvas: Canvas, x: Float, y: Float, isUp: Boolean) {
        val dir = if (isUp) -1f else 1f
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x - 8f, y + dir * 15f)
            lineTo(x + 8f, y + dir * 15f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }
}
