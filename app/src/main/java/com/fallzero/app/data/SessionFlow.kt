package com.fallzero.app.data

/**
 * 사용자가 검사/운동 세션 중에 핸드폰을 조작할 일이 없도록 만드는 자동 진행 큐.
 *
 * 한 세션의 모든 단계(준비/운동/휴식/회전)가 미리 큐로 만들어지고,
 * 각 Fragment는 완료 시 [advance]만 호출하면 다음 단계로 자동 이동합니다.
 *
 * 동시 실행 세션은 1개만 허용됩니다 (앱 전체 싱글톤).
 */
object SessionFlow {

    enum class StepType {
        PRE_FLIGHT,        // 핸드폰 직립 + 전신 감지
        EXERCISE,          // 일반 운동 1개
        EXAM_CHAIR_STAND,  // 검사: 30초 의자 일어서기
        EXAM_BALANCE,      // 검사: 균형 검사 (stage 1~4를 한 번에)
        REST,              // 휴식 카운트다운
        SIDE_REST,         // 양측 운동 좌→우 전환 (5초)
        SIDE_ROTATION,     // 정면 → 측면 전환 (1회)
        DONE               // 세션 완료
    }

    data class Step(
        val type: StepType,
        /** EXERCISE 단계일 때만: 운동 ID (1~8) */
        val exerciseId: Int = 0,
        /** REST/SIDE_REST 단계일 때만: 카운트다운 초 */
        val restSeconds: Int = 0,
        /** 표시용 메시지 */
        val title: String = "",
        val subtitle: String = ""
    )

    enum class SessionType { EXAM, EXERCISE, NONE }

    @Volatile private var steps: List<Step> = emptyList()
    @Volatile private var index: Int = 0
    @Volatile var sessionType: SessionType = SessionType.NONE
        private set

    /**
     * "홈에 들렀다가 자동으로 사전 점검으로 forward해야 함"을 표시.
     * 온보딩 설문 완료 직후에만 true가 되며, HomeFragment가 forward 후 false로 클리어.
     * 일반 백 네비게이션과 구분하기 위함.
     */
    @Volatile var pendingAutoForward: Boolean = false

    /** 운동 세션 큐 빌드: 정면(2,8) → 측면 회전 → 측면(1,3,4,5,6,7). 사이마다 휴식. */
    fun startExerciseSession() {
        sessionType = SessionType.EXERCISE
        index = 0
        val list = mutableListOf<Step>()
        list += Step(StepType.PRE_FLIGHT, title = "운동 준비",
            subtitle = "핸드폰을 수직으로 세우고 전신이 보이도록 서주세요.")

        // 정면 운동 그룹
        val frontGroup = listOf(2, 8)
        // 측면 운동 그룹 (좌/우 회전 1회 후 모두 측면)
        val sideGroup = listOf(1, 3, 4, 5, 6, 7)

        frontGroup.forEachIndexed { i, id ->
            list += Step(StepType.EXERCISE, exerciseId = id, title = exerciseName(id))
            if (i < frontGroup.size - 1) list += Step(StepType.REST, restSeconds = REST_SECONDS,
                title = "잠시 쉬어가요", subtitle = "${REST_SECONDS}초 후 다음 운동이 시작됩니다.")
        }
        // 회전 단계
        list += Step(StepType.SIDE_ROTATION, title = "옆으로 돌아주세요",
            subtitle = "이제 측면 운동입니다. 카메라가 옆모습을 볼 수 있게 옆으로 90도 돌아주세요.")
        sideGroup.forEachIndexed { i, id ->
            list += Step(StepType.EXERCISE, exerciseId = id, title = exerciseName(id))
            if (i < sideGroup.size - 1) list += Step(StepType.REST, restSeconds = REST_SECONDS,
                title = "잠시 쉬어가요", subtitle = "${REST_SECONDS}초 후 다음 운동이 시작됩니다.")
        }
        list += Step(StepType.DONE)
        steps = list
    }

    /**
     * 단일 운동(개별 선택) 큐: 사전 점검 → (측면 운동이면 회전) → 운동 1개 → 종료
     * #2(고관절 외전), #8(균형)는 정면 운동 — 회전 불필요.
     * 나머지는 측면 운동 — 사전 점검(정면 감지) 후 옆으로 회전 필요.
     */
    fun startSingleExercise(exerciseId: Int) {
        sessionType = SessionType.EXERCISE
        index = 0
        val list = mutableListOf<Step>()
        list += Step(StepType.PRE_FLIGHT, title = "운동 준비",
            subtitle = "핸드폰을 수직으로 세우고 전신이 보이도록 서주세요.")
        // 측면 운동이면 회전 단계 추가
        if (exerciseId !in FRONT_EXERCISES) {
            list += Step(StepType.SIDE_ROTATION, title = "옆으로 돌아주세요",
                subtitle = "이 운동은 측면에서 측정합니다. 옆으로 90도 돌아주세요.")
        }
        list += Step(StepType.EXERCISE, exerciseId = exerciseId, title = exerciseName(exerciseId))
        list += Step(StepType.DONE)
        steps = list
    }

    /** 정면 촬영 운동 ID (회전 불필요) */
    val FRONT_EXERCISES = setOf(2, 8)

    /** 디버그: 균형 검사 건너뛰고 의자 일어서기만 */
    fun startExamChairStandOnly() {
        sessionType = SessionType.EXAM
        index = 0
        steps = listOf(
            Step(StepType.PRE_FLIGHT, title = "검사 준비 (디버그)",
                subtitle = "핸드폰을 수직으로 세우고 전신이 보이도록 서주세요."),
            Step(StepType.EXAM_CHAIR_STAND, title = "30초 의자 일어서기 검사"),
            Step(StepType.DONE)
        )
    }

    /** 검사 세션 큐 빌드: 정면(균형) → 회전 → 측면(의자일어서기). */
    fun startExamSession() {
        sessionType = SessionType.EXAM
        index = 0
        steps = listOf(
            Step(StepType.PRE_FLIGHT, title = "검사 준비",
                subtitle = "핸드폰을 수직으로 세우고 전신이 보이도록 서주세요."),
            Step(StepType.EXAM_BALANCE, title = "균형 검사"),
            // 정면에서 바로 의자 검사 (회전 불필요 — 정면 D% 범위가 더 넓어 감지 정확)
            Step(StepType.EXAM_CHAIR_STAND, title = "30초 의자 일어서기 검사"),
            Step(StepType.DONE)
        )
    }

    fun current(): Step = steps.getOrNull(index) ?: Step(StepType.DONE)

    /** 다음 단계로 진행. 큐의 끝이면 [StepType.DONE] 반환. */
    fun advance(): Step {
        if (index < steps.size - 1) index++
        return current()
    }

    fun reset() {
        steps = emptyList()
        index = 0
        sessionType = SessionType.NONE
        pendingAutoForward = false
    }

    /** 의자가 필요한 운동 ID (사용자에게 사전 안내용) */
    val CHAIR_REQUIRED_EXERCISES = setOf(1, 7)

    /** 의자가 필요한 운동이 큐에 포함되어 있는가? */
    fun requiresChair(): Boolean = steps.any { it.exerciseId in CHAIR_REQUIRED_EXERCISES }

    const val REST_SECONDS = 15
    const val SIDE_REST_SECONDS = 5

    /** 운동 ID → 한국어 이름 (고령층 친화적) */
    fun exerciseName(id: Int): String = when (id) {
        1 -> "앉아서 무릎 펴기"
        2 -> "옆으로 다리 들기"
        3 -> "뒤로 무릎 굽히기"
        4 -> "발뒤꿈치 들기"
        5 -> "발끝 들기"
        6 -> "무릎 살짝 굽히기"
        7 -> "의자에서 일어서기"
        8 -> "한 발로 서서 균형 잡기"
        else -> "운동"
    }

    /** 운동이 양측인지(좌/우 따로) */
    fun isBilateral(id: Int): Boolean = id in setOf(1, 2, 3)
}
