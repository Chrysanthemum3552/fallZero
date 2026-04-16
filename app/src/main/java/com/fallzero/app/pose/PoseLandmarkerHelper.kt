package com.fallzero.app.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener
) {

    private var poseLandmarker: PoseLandmarker? = null
    private var lastFrameTimeMs: Long = 0L
    @Volatile private var isProcessing = false
    @Volatile private var isClosed = false
    private val landmarkerLock = Any()

    interface LandmarkerListener {
        fun onResults(resultBundle: ResultBundle)
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
    }

    data class ResultBundle(
        val results: List<PoseLandmarkerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int
    )

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {
        try {
            // 모델 파일 자동 선택: full → lite 순으로 fallback (다운로드 실패/누락 대비)
            val modelPath = pickAvailableModel(context)
            if (modelPath == null) {
                listener.onError("MediaPipe 모델 파일이 없습니다. 앱을 재설치해주세요.", OTHER_ERROR)
                return
            }
            Log.d(TAG, "Using model: $modelPath")
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelPath)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(MIN_POSE_DETECTION_CONFIDENCE)
                .setMinTrackingConfidence(MIN_POSE_TRACKING_CONFIDENCE)
                .setMinPosePresenceConfidence(MIN_POSE_PRESENCE_CONFIDENCE)
                .setNumPoses(MAX_NUM_POSES)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, input ->
                    isProcessing = false
                    if (isClosed) return@setResultListener
                    val finishTimeMs = SystemClock.uptimeMillis()
                    val inferenceTime = finishTimeMs - lastFrameTimeMs
                    try {
                        listener.onResults(
                            ResultBundle(
                                listOf(result),
                                inferenceTime,
                                input.height,
                                input.width
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Result listener error", e)
                    }
                }
                .setErrorListener { error ->
                    isProcessing = false
                    if (isClosed) return@setErrorListener
                    Log.e(TAG, "MediaPipe error: ${error.message}")
                    try {
                        listener.onError(error.message ?: "MediaPipe error", GPU_ERROR)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error listener error", e)
                    }
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.d(TAG, "PoseLandmarker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "PoseLandmarker init failed", e)
            listener.onError("MediaPipe 초기화 실패: ${e.message}", OTHER_ERROR)
        }
    }

    /**
     * 카메라 프레임을 처리합니다. ImageProxy는 이 메서드 내에서 항상 close됩니다.
     */
    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        if (isClosed || poseLandmarker == null || isProcessing) {
            try { imageProxy.close() } catch (_: Exception) {}
            return
        }

        var bitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null
        try {
            // 단조 증가하는 frameTime 보장 (MediaPipe는 동일 timestamp 시 native crash 발생)
            val nowMs = SystemClock.uptimeMillis()
            val frameTime = if (nowMs <= lastFrameTimeMs) lastFrameTimeMs + 1 else nowMs

            bitmap = imageProxyToBitmap(imageProxy)
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            try { imageProxy.close() } catch (_: Exception) {}

            if (bitmap == null) return

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                }
            }
            rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            // 회전된 비트맵이 원본과 다르면 원본 해제 (메모리 누수 방지)
            if (rotatedBitmap !== bitmap && !bitmap.isRecycled) {
                try { bitmap.recycle() } catch (_: Exception) {}
            }

            synchronized(landmarkerLock) {
                if (isClosed) return
                val landmarker = poseLandmarker ?: return
                isProcessing = true
                lastFrameTimeMs = frameTime
                val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                try {
                    landmarker.detectAsync(mpImage, frameTime)
                } catch (e: Exception) {
                    Log.e(TAG, "detectAsync failed", e)
                    isProcessing = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectLiveStream error", e)
            isProcessing = false
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    /**
     * ImageProxy → Bitmap 변환.
     * ImageProxy.toBitmap()을 사용하여 Samsung 기기의 비표준 YUV stride 문제를 회피.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.e(TAG, "toBitmap failed, trying manual conversion", e)
            try {
                manualYuvToBitmap(imageProxy)
            } catch (e2: Exception) {
                Log.e(TAG, "Manual conversion also failed", e2)
                null
            }
        }
    }

    /**
     * toBitmap() 실패 시 폴백: YUV → NV21 → JPEG → Bitmap
     */
    private fun manualYuvToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val width = imageProxy.width
        val height = imageProxy.height

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Y plane — row stride 고려하여 복사
        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, row * width, width)
        }

        // UV planes — pixelStride에 따라 다른 처리
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        var offset = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                nv21[offset++] = vBuffer.get(uvIndex)
                nv21[offset++] = uBuffer.get(uvIndex)
            }
        }

        val yuvImage = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21, width, height, null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
        return android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    fun clearPoseLandmarker() {
        synchronized(landmarkerLock) {
            isClosed = true
            try {
                poseLandmarker?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing PoseLandmarker", e)
            }
            poseLandmarker = null
            isProcessing = false
        }
    }

    companion object {
        private const val TAG = "PoseLandmarkerHelper"
        const val MODEL_POSE_LANDMARKER_HEAVY = "pose_landmarker_heavy.task"
        const val MODEL_POSE_LANDMARKER_FULL = "pose_landmarker_full.task"
        const val MODEL_POSE_LANDMARKER_LITE = "pose_landmarker_lite.task"
        const val MIN_POSE_DETECTION_CONFIDENCE = 0.5f
        const val MIN_POSE_TRACKING_CONFIDENCE = 0.5f
        const val MIN_POSE_PRESENCE_CONFIDENCE = 0.5f
        const val MAX_NUM_POSES = 1
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1

        /** assets 폴더에서 사용 가능한 모델을 선택. heavy → full → lite 순으로 fallback. */
        private fun pickAvailableModel(context: android.content.Context): String? {
            val candidates = listOf(MODEL_POSE_LANDMARKER_HEAVY, MODEL_POSE_LANDMARKER_FULL, MODEL_POSE_LANDMARKER_LITE)
            for (path in candidates) {
                try {
                    context.assets.open(path).use { return path }
                } catch (_: Exception) { /* try next */ }
            }
            return null
        }
    }
}
