package com.fallzero.app.ui.exercise

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.algorithm.BalanceProgressionManager
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.databinding.FragmentExerciseGuideBinding
import kotlinx.coroutines.launch
import java.util.Calendar

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

    // 표시 순서는 SessionFlow.EXERCISE_DISPLAY_ORDER가 단일 source of truth.
    // 실제 세션 큐: 정면-서서(2,8) → 정면-앉기(7) → 회전 → 측면-서서(4,5,6,3) → 의자 → 측면-앉기(1)
    private val exerciseInfoMap = mapOf(
        1 to ExerciseInfo(1, "앉아서 무릎 펴기", "의자에 앉아 한쪽 다리를 앞으로 쭉 뻗어 올렸다 내리기 (좌우 각 10회)", "측면 촬영"),
        2 to ExerciseInfo(2, "옆으로 다리 들기", "벽이나 의자를 잡고 서서 한쪽 다리를 옆으로 들어 올렸다 내리기 (좌우 각 10회)", "정면 촬영"),
        3 to ExerciseInfo(3, "뒤로 무릎 굽히기", "벽이나 의자를 잡고 서서 한쪽 무릎을 뒤로 굽혀 발뒤꿈치 올리기 (좌우 각 10회)", "측면 촬영"),
        4 to ExerciseInfo(4, "발뒤꿈치 들기", "양발 발뒤꿈치를 동시에 들어 올렸다 내리기 (10회)", "측면 촬영"),
        5 to ExerciseInfo(5, "발끝 들기", "양발 발끝을 들어 올렸다 내리기 (10회)", "측면 촬영"),
        6 to ExerciseInfo(6, "무릎 살짝 굽히기", "양발 어깨 너비로 벌리고 무릎을 살짝 굽혔다 펴기 (10회)", "측면 촬영"),
        7 to ExerciseInfo(7, "의자에서 일어서기", "의자에서 팔을 가슴에 교차한 채 일어섰다 앉기 (10회)", "정면 촬영"),
        8 to ExerciseInfo(8, "한 발로 서서 균형 잡기", "일렬 서기와 외발 서기로 균형 유지", "정면 촬영")
    )
    private val exercises: List<ExerciseInfo> = SessionFlow.EXERCISE_DISPLAY_ORDER.mapNotNull { exerciseInfoMap[it] }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExerciseGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.rvExercises.layoutManager = LinearLayoutManager(requireContext())

        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)

        // 비동기로 오늘 완료 운동 로드 후 어댑터에 주입 (홈 체크리스트와 동일 패턴)
        viewLifecycleOwner.lifecycleScope.launch {
            val todayStart = getTodayStartMillis()
            val completedIds = FallZeroDatabase.getInstance(requireContext())
                .sessionDao().getTodayCompletedExerciseIds(userId, todayStart).toSet()
            if (_binding == null) return@launch
            binding.rvExercises.adapter = ExerciseAdapter(
                exercises,
                completedIds,
                { id -> setLabelFor(id, prefs) }
            ) { exercise ->
                handleExerciseClick(exercise, prefs, userId)
            }
        }

        binding.btnStartAll.setOnClickListener {
            // 전체 운동 루틴 — 미리보기 건너뛰고 사전 점검부터 자동 진행
            SessionFlow.startExerciseSession()
            findNavController().navigate(R.id.action_guide_to_preflight)
        }
    }

    private fun handleExerciseClick(exercise: ExerciseInfo, prefs: SharedPreferences, userId: Int) {
        // 개별 운동 — 미리보기 화면 표시 (SessionFlow 미사용)
        // 단, 오늘 미완료 운동이 이 운동 이후로도 있으면 "resume" 플래그를 세워
        // 미리보기 화면의 시작 버튼이 단일 운동 대신 부분 세션을 빌드하게 한다.
        prefs.edit().putInt("selected_exercise_id", exercise.id).apply()
        prefs.edit().remove("resume_from_exercise_id").apply()
        SessionFlow.reset()

        viewLifecycleOwner.lifecycleScope.launch {
            val todayStart = getTodayStartMillis()
            val completed = FallZeroDatabase.getInstance(requireContext())
                .sessionDao().getTodayCompletedExerciseIds(userId, todayStart).toSet()
            val resumeQueue = SessionFlow.EXERCISE_DISPLAY_ORDER
                .dropWhile { it != exercise.id }
                .filter { it == exercise.id || it !in completed }
            if (resumeQueue.size > 1) {
                prefs.edit()
                    .putString("resume_from_exercise_id", resumeQueue.joinToString(","))
                    .apply()
            }
            if (_binding != null) {
                findNavController().navigate(R.id.action_guide_to_preview)
            }
        }
    }

    /** 운동별 진급 상태 — 균형: 단계+시간, 근력: 1세트 / 2세트. 홈 체크리스트와 동일. */
    private fun setLabelFor(exerciseId: Int, prefs: SharedPreferences): String {
        return if (exerciseId == 8) {
            val stage = prefs.getInt("current_set_level", 1).coerceIn(1, 5)
            val level = BalanceProgressionManager.getLevel(stage)
            "${level.description} ${level.targetTimeSec.toInt()}초"
        } else {
            val sets = prefs.getInt("set_level_ex_$exerciseId", 1).coerceIn(1, 2)
            "${sets}세트"
        }
    }

    /** 오늘 자정 millis — DAO와 동일 기준. */
    private fun getTodayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── RecyclerView Adapter ──
    class ExerciseAdapter(
        private val items: List<ExerciseInfo>,
        private val completedIds: Set<Int>,
        private val setLabelProvider: (Int) -> String,
        private val onClick: (ExerciseInfo) -> Unit
    ) : RecyclerView.Adapter<ExerciseAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNumber: TextView = view.findViewById(R.id.tv_number)
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvDesc: TextView = view.findViewById(R.id.tv_desc)
            val tvCameraTag: TextView = view.findViewById(R.id.tv_camera_tag)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
            val tvSetInfo: TextView = view.findViewById(R.id.tv_set_info)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exercise, parent, false)
            return ViewHolder(view)
        }

        // "다음 차례" 판정: completedIds에 없는 첫 번째 운동 (표시 순서 기준)
        private val nextExerciseId: Int? = items.firstOrNull { it.id !in completedIds }?.id

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            val isDone = item.id in completedIds
            val isNext = item.id == nextExerciseId

            holder.tvNumber.text = (position + 1).toString()
            holder.tvName.text = item.name
            holder.tvDesc.text = item.description
            holder.tvCameraTag.text = item.cameraDirection
            holder.tvSetInfo.text = setLabelProvider(item.id)

            // 3상태: 완료(success "완료") · 다음 차례(primary "다음") · 대기(빈 텍스트, 운동명 일반)
            when {
                isDone -> {
                    holder.tvStatus.text = "완료"
                    holder.tvStatus.setTextColor(ctx.resources.getColor(R.color.success, null))
                    holder.tvName.setTextColor(ctx.resources.getColor(R.color.text_secondary, null))
                }
                isNext -> {
                    holder.tvStatus.text = "다음"
                    holder.tvStatus.setTextColor(ctx.resources.getColor(R.color.primary, null))
                    holder.tvName.setTextColor(ctx.resources.getColor(R.color.text_primary, null))
                }
                else -> {
                    holder.tvStatus.text = ""
                    holder.tvName.setTextColor(ctx.resources.getColor(R.color.text_primary, null))
                }
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
