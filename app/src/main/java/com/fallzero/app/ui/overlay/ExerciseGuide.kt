package com.fallzero.app.ui.overlay

/**
 * 운동·검사 중 "어디까지 해야 1회 카운트되는지" 시각화 데이터.
 *
 * Engine.getGuide(landmarks)가 반환. 사용자 화면 좌표 의존도 0 — perspective/jitter 영향 없음.
 *
 * 두 종류:
 *  - [Bar]: 운동 진행도 (0~1). 수직/수평/양방향 + 채움 방향 + 운동명.
 *  - [Bubble]: 균형 운동 — 자세 안정성 (sway). 안전 영역 안에 버블이 머물면 도달.
 *
 * 모두 perspective 무관 — 단순 수치 0~1만 표시. landmark 떨림에도 안정.
 */
sealed class ExerciseGuide {

    /** 운동 진행도 표시 (8개 운동 중 7개 + 검사 의자 일어서기) */
    data class Bar(
        /** 0.0 ~ 1.0 — 현재 진행도 (motionThreshold 도달 시 1.0) */
        val progress: Float,
        /** 막대 방향: 수직(true) 또는 수평(false) */
        val vertical: Boolean,
        /** 채움 방향: 운동 방향과 일치하도록 운동별로 다름.
         *  vertical=true: UP(아래→위) 또는 DOWN(위→아래)
         *  vertical=false: LEFT(우→좌) 또는 RIGHT(좌→우) 또는 BOTH(중앙→양 끝, lockedSide에 따라) */
        val fillDirection: FillDirection,
        /** 운동 이름 라벨 (예: "발끝 들기") */
        val label: String,
        /** 도달 직후 한 번 점프 애니메이션 트리거 — true가 들어온 frame에서만 점프 */
        val justReached: Boolean = false,
    ) : ExerciseGuide()

    /** 균형 자세 안정성 표시 (#8 균형 운동 + 검사 균형 4단계).
     *  사용자 명시: 거대한 원형 링 + 가운데 큰 노란 숫자. 시계방향 채움.
     *  색상: 안정(swayRatio<0.5)=초록, 보통(<1.0)=노랑, 흔들/자세 틀림=빨강 */
    data class Bubble(
        /** 현재 sway (0 = 안정 중앙, 1+ = 흔들림 큼) */
        val swayRatio: Float,
        /** 안전 영역 진입 후 경과 시간 / 목표 유지 시간 (0.0 ~ 1.0). 목표 시간 도달 시 1.0 */
        val holdProgress: Float,
        /** 라벨 (예: "한 발 균형") */
        val label: String,
        /** 안전 영역 유지 경과 초 — 링 가운데 표시 */
        val elapsedSec: Float = 0f,
        /** 목표 시간 (10초 등) */
        val targetSec: Float = 10f,
        /** 자세 자체가 valid 한지 (일렬/한 발 서기 자세 잘못 잡았으면 false) — 적색 트리거 */
        val poseValid: Boolean = true,
    ) : ExerciseGuide()

    enum class FillDirection { UP, DOWN, LEFT, RIGHT, FROM_CENTER_LEFT, FROM_CENTER_RIGHT }
}
