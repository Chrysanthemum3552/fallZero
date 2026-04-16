package com.fallzero.app.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: PoseLandmarkerResult? = null
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private val landmarkPaint = Paint().apply {
        color = Color.parseColor("#FF4081")
        strokeWidth = 12f
        style = Paint.Style.FILL
    }

    private val connectionPaint = Paint().apply {
        color = Color.parseColor("#00BCD4")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    // MediaPipe Pose 연결 정의 (주요 관절 연결)
    private val connections = listOf(
        Pair(11, 12), // 어깨
        Pair(11, 13), Pair(13, 15), // 왼팔
        Pair(12, 14), Pair(14, 16), // 오른팔
        Pair(11, 23), Pair(12, 24), // 몸통
        Pair(23, 24), // 골반
        Pair(23, 25), Pair(25, 27), // 왼다리
        Pair(24, 26), Pair(26, 28)  // 오른다리
    )

    fun setResults(
        poseLandmarkerResult: PoseLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int
    ) {
        this.results = poseLandmarkerResult
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    fun clear() {
        results = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = results ?: return
        if (result.landmarks().isEmpty()) return

        val landmarks = result.landmarks()[0]
        val w = width.toFloat()
        val h = height.toFloat()

        // NormalizedLandmark의 x(), y()는 0~1 정규화 좌표 → 뷰 크기에 직접 곱함
        for ((start, end) in connections) {
            if (start < landmarks.size && end < landmarks.size) {
                val startLm = landmarks[start]
                val endLm = landmarks[end]
                canvas.drawLine(
                    startLm.x() * w,
                    startLm.y() * h,
                    endLm.x() * w,
                    endLm.y() * h,
                    connectionPaint
                )
            }
        }

        // 랜드마크 점 그리기
        for (landmark in landmarks) {
            canvas.drawCircle(
                landmark.x() * w,
                landmark.y() * h,
                8f,
                landmarkPaint
            )
        }
    }
}
