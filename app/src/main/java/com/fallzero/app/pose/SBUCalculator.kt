package com.fallzero.app.pose

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import kotlin.math.sqrt

object SBUCalculator {

    /**
     * SBU (Shoulder-to-Belt-Unit): 어깨-골반 사이 거리 (정규화 기준값)
     * 운동 동작의 크기를 체형에 맞게 정규화하는 데 사용
     */
    fun calculate(landmarks: List<NormalizedLandmark>): Float {
        if (landmarks.size <= maxOf(LandmarkIndex.LEFT_SHOULDER, LandmarkIndex.LEFT_HIP)) {
            return 0f
        }

        val leftShoulder = landmarks[LandmarkIndex.LEFT_SHOULDER]
        val rightShoulder = landmarks[LandmarkIndex.RIGHT_SHOULDER]
        val leftHip = landmarks[LandmarkIndex.LEFT_HIP]
        val rightHip = landmarks[LandmarkIndex.RIGHT_HIP]

        val shoulderMidX = (leftShoulder.x() + rightShoulder.x()) / 2
        val shoulderMidY = (leftShoulder.y() + rightShoulder.y()) / 2
        val hipMidX = (leftHip.x() + rightHip.x()) / 2
        val hipMidY = (leftHip.y() + rightHip.y()) / 2

        val dx = shoulderMidX - hipMidX
        val dy = shoulderMidY - hipMidY
        return sqrt(dx * dx + dy * dy)
    }
}
