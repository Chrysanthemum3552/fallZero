package com.fallzero.app.pose

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.acos
import kotlin.math.sqrt

object AngleCalculator {

    /**
     * 세 랜드마크로 이루어진 각도 계산 (도 단위)
     * pointA - pointB - pointC 에서 B를 꼭짓점으로 하는 각도
     */
    fun calculateAngle(
        pointA: NormalizedLandmark,
        pointB: NormalizedLandmark,
        pointC: NormalizedLandmark
    ): Float {
        val mag1 = sqrt(
            (pointA.x() - pointB.x()).pow2() + (pointA.y() - pointB.y()).pow2()
        )
        val mag2 = sqrt(
            (pointC.x() - pointB.x()).pow2() + (pointC.y() - pointB.y()).pow2()
        )
        if (mag1 == 0f || mag2 == 0f) return 0f

        val dotProduct = (pointA.x() - pointB.x()) * (pointC.x() - pointB.x()) +
            (pointA.y() - pointB.y()) * (pointC.y() - pointB.y())
        val cosAngle = (dotProduct / (mag1 * mag2)).coerceIn(-1f, 1f)
        val radians = acos(cosAngle)
        return Math.toDegrees(radians.toDouble()).toFloat()
    }

    private fun Float.pow2() = this * this

    /** 랜드마크 가시성 조회 (0~1, 높을수록 신뢰) */
    fun visibility(landmark: NormalizedLandmark): Float {
        return landmark.visibility().orElse(0f)
    }

    /**
     * 좌/우 랜드마크 중 가시성이 높은 쪽을 선택.
     * 양쪽 모두 MIN_VISIBILITY 미만이면 null 반환 (신뢰 불가).
     */
    fun pickVisibleSide(
        landmarks: List<NormalizedLandmark>,
        leftIdx: Int,
        rightIdx: Int
    ): Side? {
        val leftVis = visibility(landmarks[leftIdx])
        val rightVis = visibility(landmarks[rightIdx])
        if (leftVis < MIN_VISIBILITY && rightVis < MIN_VISIBILITY) return null
        return if (leftVis >= rightVis) Side.LEFT else Side.RIGHT
    }

    const val MIN_VISIBILITY = 0.5f

    enum class Side { LEFT, RIGHT }

    // MediaPipe 랜드마크 인덱스 상수
    object LandmarkIndex {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
        const val LEFT_HEEL = 29
        const val RIGHT_HEEL = 30
        const val LEFT_FOOT_INDEX = 31
        const val RIGHT_FOOT_INDEX = 32
    }
}
