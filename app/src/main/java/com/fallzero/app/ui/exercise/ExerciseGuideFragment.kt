package com.fallzero.app.ui.exercise

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentExerciseGuideBinding

/**
 * 운동 가이드 Fragment — OEP 8개 운동 목록을 RecyclerView로 표시
 * 개별 운동 선택 시 ExercisePreviewFragment로 이동 (가이드 애니메이션 + 시작)
 * "전체 운동 시작" 버튼은 운동 1번부터 순서대로 진행
 */
class ExerciseGuideFragment : Fragment() {

    private var _binding: FragmentExerciseGuideBinding? = null
    private val binding get() = _binding!!

    data class ExerciseInfo(
        val id: Int,
        val name: String,
        val description: String,
        val cameraDirection: String  // "측면 촬영" or "정면 촬영"
    )

    // 표시 순서 = 실제 세션 순서: 정면(2,7,8) → 회전 → 측면(4,5,6,1,3)
    private val exercises = listOf(
        ExerciseInfo(2, "옆으로 다리 들기", "벽이나 의자를 잡고 서서 한쪽 다리를 옆으로 들어 올렸다 내리기 (좌우 각 10회)", "정면 촬영"),
        ExerciseInfo(7, "의자에서 일어서기", "의자에서 팔을 가슴에 교차한 채 일어섰다 앉기 (10회)", "정면 촬영"),
        ExerciseInfo(8, "한 발로 서서 균형 잡기", "탠덤 서기와 외발 서기로 균형 유지", "정면 촬영"),
        ExerciseInfo(4, "발뒤꿈치 들기", "양발 발뒤꿈치를 동시에 들어 올렸다 내리기 (10회)", "측면 촬영"),
        ExerciseInfo(5, "발끝 들기", "양발 발끝을 들어 올렸다 내리기 (10회)", "측면 촬영"),
        ExerciseInfo(6, "무릎 살짝 굽히기", "양발 어깨 너비로 벌리고 무릎을 살짝 굽혔다 펴기 (10회)", "측면 촬영"),
        ExerciseInfo(1, "앉아서 무릎 펴기", "의자에 앉아 한쪽 다리를 앞으로 쭉 뻗어 올렸다 내리기 (좌우 각 10회)", "측면 촬영"),
        ExerciseInfo(3, "뒤로 무릎 굽히기", "벽이나 의자를 잡고 서서 한쪽 무릎을 뒤로 굽혀 발뒤꿈치 올리기 (좌우 각 10회)", "측면 촬영")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExerciseGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvExercises.layoutManager = LinearLayoutManager(requireContext())
        binding.rvExercises.adapter = ExerciseAdapter(exercises) { exercise ->
            // 개별 운동 — 미리보기 화면 표시 (SessionFlow 미사용)
            val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("selected_exercise_id", exercise.id).apply()
            SessionFlow.reset()
            findNavController().navigate(R.id.action_guide_to_preview)
        }

        binding.btnStartAll.setOnClickListener {
            // 전체 운동 루틴 — 미리보기 건너뛰고 사전 점검부터 자동 진행
            SessionFlow.startExerciseSession()
            findNavController().navigate(R.id.action_guide_to_preflight)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── RecyclerView Adapter ──
    class ExerciseAdapter(
        private val items: List<ExerciseInfo>,
        private val onClick: (ExerciseInfo) -> Unit
    ) : RecyclerView.Adapter<ExerciseAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNumber: TextView = view.findViewById(R.id.tv_number)
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvDesc: TextView = view.findViewById(R.id.tv_desc)
            val tvCameraTag: TextView = view.findViewById(R.id.tv_camera_tag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exercise, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            // 표시 순서 번호 (운동 ID가 아니라 1~8 순서)
            holder.tvNumber.text = (position + 1).toString()
            holder.tvName.text = item.name
            holder.tvDesc.text = item.description
            holder.tvCameraTag.text = item.cameraDirection
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
