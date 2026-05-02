package com.fallzero.app.pose

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.acos
import kotlin.math.sqrt

object AngleCalculator {

    /**
     * 세 랜드마크로 이루어진 각도 계산 (도 단위).
     * pointA - pointB - pointC 에서 B를 꼭짓점으로 하는 각도.
     *
     * 최적화: NormalizedLandmark.x()/y()는 JNI 호출 — 매 호출 ~100ns.
     *   기존: 12회 JNI (각 좌표 2회 접근). 개선: 6회 JNI (지역 변수 캐싱).
     *   엔진당 frame당 2~6회 호출 × 30fps = JNI overhead 대폭 절감.
     *   sqrt(a)*sqrt(b) → sqrt(a*b) (수학적 동일) 로 sqrt 1회 절약.
     */
    fun calculateAngle(
        pointA: NormalizedLandmark,
        pointB: NormalizedLandmark,
        pointC: NormalizedLandmark
    ): Float {
        val ax = pointA.x(); val ay = pointA.y()
        val bx = pointB.x(); val by = pointB.y()
        val cx = pointC.x(); val cy = pointC.y()

        val dx1 = ax - bx; val dy1 = ay - by
        val dx2 = cx - bx; val dy2 = cy - by

        val mag1Sq = dx1 * dx1 + dy1 * dy1
        val mag2Sq = dx2 * dx2 + dy2 * dy2
        if (mag1Sq == 0f || mag2Sq == 0f) return 0f

        val dot = dx1 * dx2 + dy1 * dy2
        val cosAngle = (dot / sqrt(mag1Sq * mag2Sq)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosAngle).toDouble()).toFloat()
    }

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
