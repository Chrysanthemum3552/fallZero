package com.fallzero.app.ui.exercise

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentExercisePreviewBinding

/**
 * 운동 시작 전 프리뷰 화면
 * - Lottie 애니메이션이 있으면 자동 재생
 * - 없으면 텍스트 설명만 표시
 * - "이 운동 시작하기" → CameraSetupFragment로 이동
 */
class ExercisePreviewFragment : Fragment() {

    private var _binding: FragmentExercisePreviewBinding? = null
    private val binding get() = _binding!!

    // 운동별 상세 설명 (고령자가 이해할 수 있는 쉬운 말로)
    private val exerciseDetails = mapOf(
        1 to Triple("앉아서 무릎 펴기", "의자에 앉은 상태에서\n한쪽 다리를 앞으로 쭉 뻗어 올렸다가\n천천히 내려주세요.\n\n왼쪽 10회 → 오른쪽 10회", "측면에서 촬영합니다 (의자 필요)"),
        2 to Triple("옆으로 다리 들기", "벽이나 의자를 잡고 서서\n한쪽 다리를 옆으로 천천히 들어 올렸다가\n내려주세요.\n\n왼쪽 10회 → 오른쪽 10회", "정면에서 촬영합니다"),
        3 to Triple("뒤로 무릎 굽히기", "벽이나 의자를 잡고 서서\n한쪽 무릎을 뒤로 굽혀\n발뒤꿈치를 엉덩이 쪽으로 올려주세요.\n\n왼쪽 10회 → 오른쪽 10회", "측면에서 촬영합니다"),
        4 to Triple("발뒤꿈치 들기", "벽이나 의자를 잡고 서서\n양쪽 발뒤꿈치를 동시에\n천천히 들어 올렸다 내려주세요.\n\n10회", "측면에서 촬영합니다"),
        5 to Triple("발끝 들기", "벽이나 의자를 잡고 서서\n양쪽 발끝을 들어 올려\n발목을 위로 꺾어주세요.\n\n10회", "측면에서 촬영합니다"),
        6 to Triple("무릎 살짝 굽히기", "벽이나 의자를 잡고 서서\n양발을 어깨 너비로 벌린 후\n무릎을 살짝 굽혔다 펴주세요.\n\n10회", "측면에서 촬영합니다"),
        7 to Triple("의자에서 일어서기", "의자에 앉아 팔을 가슴에 교차한 채\n일어섰다 앉는 동작을 반복해주세요.\n\n10회", "측면에서 촬영합니다 (의자 필요)"),
        8 to Triple("한 발로 서서 균형 잡기", "탠덤 서기(한 발 앞에 다른 발)와\n외발 서기를 수행합니다.\n각 자세를 목표 시간 동안 유지해주세요.", "정면에서 촬영합니다")
    )

    private var exerciseId: Int = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExercisePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        exerciseId = prefs.getInt("selected_exercise_id", 1)
        val detail = exerciseDetails[exerciseId] ?: return

        binding.tvExerciseName.text = detail.first
        binding.tvDescription.text = detail.second
        binding.tvCameraDirection.text = detail.third

        // 스틱맨 애니메이션 표시 (Lottie 파일이 없으므로 항상 ExerciseAnimationView 사용)
        binding.lottieGuide.visibility = View.GONE
        binding.exerciseAnimation.visibility = View.VISIBLE
        binding.exerciseAnimation.setExercise(exerciseId)

        binding.btnStart.setOnClickListener {
            // SessionFlow가 이미 활성(전체 루틴) → preflight로 바로 진입
            // 비활성(개별 운동) → 단일 운동을 큐에 넣고 시작
            if (SessionFlow.sessionType == SessionFlow.SessionType.NONE) {
                SessionFlow.startSingleExercise(exerciseId)
            }
            findNavController().navigate(R.id.action_preview_to_preflight)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
