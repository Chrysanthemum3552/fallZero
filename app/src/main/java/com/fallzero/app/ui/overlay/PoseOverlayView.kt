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

    // MediaPipe Pose 연결 정의 — 병렬 IntArray로 저장 (Pair<Int,Int>는 destructuring 시 Integer 박싱).
    // 매 frame 12회 destructure × 30fps = 360회 boxing 회피.
    //                       어깨,왼팔1,왼팔2,오팔1,오팔2,몸통L,몸통R,골반,왼다1,왼다2,오다1,오다2
    private val connStarts = intArrayOf(11,    11,   13,   12,   14,   11,    12,    23,   23,   25,   24,   26)
    private val connEnds   = intArrayOf(12,    13,   15,   14,   16,   23,    24,    24,   25,   27,   26,   28)

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
        val allLandmarks = result.landmarks()
        if (allLandmarks.isEmpty()) return

        val landmarks = allLandmarks[0]
        val landmarksSize = landmarks.size
        val w = width.toFloat()
        val h = height.toFloat()

        // 연결선 — IntArray index 기반 (boxing 회피)
        for (i in connStarts.indices) {
            val start = connStarts[i]
            val end = connEnds[i]
            if (start < landmarksSize && end < landmarksSize) {
                val s = landmarks[start]
                val e = landmarks[end]
                canvas.drawLine(s.x() * w, s.y() * h, e.x() * w, e.y() * h, connectionPaint)
            }
        }

        // 랜드마크 점 그리기 — index loop (Iterator 할당 회피)
        for (i in 0 until landmarksSize) {
            val lm = landmarks[i]
            canvas.drawCircle(lm.x() * w, lm.y() * h, 8f, landmarkPaint)
        }
    }
}
