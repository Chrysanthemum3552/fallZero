package com.fallzero.app.ui.report

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.databinding.FragmentReportBinding
import kotlinx.coroutines.launch

/**
 * 운동 기록 화면 — 8개 운동 각각의 최근 5회 record를 반복별 음성피드백 트래커로 표시.
 * 운동 외 정보(검사 결과·진급 단계·이행률)는 표시하지 않는다.
 */
class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    private val exerciseNames = mapOf(
        1 to "앉아서 무릎 펴기", 2 to "옆으로 다리 들기", 3 to "뒤로 무릎 굽히기",
        4 to "발뒤꿈치 들기", 5 to "발끝 들기", 6 to "무릎 살짝 굽히기",
        7 to "의자에서 일어서기", 8 to "한 발로 서기"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        viewLifecycleOwner.lifecycleScope.launch {
            loadAbilityDetails(binding)
        }
    }

    /** 8개 운동 카드를 순서대로 빌드. 각 카드 안에서 좌우 스와이프로 최근 5개 record 개별 확인. */
    private suspend fun loadAbilityDetails(b: FragmentReportBinding) {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        val container = b.layoutAbilityCards
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        SessionFlow.EXERCISE_DISPLAY_ORDER.forEach { exerciseId ->
            val records = db.sessionDao().getRecentRecordsByExercise(userId, exerciseId, 5)
            val section = inflater.inflate(R.layout.item_ability_section, container, false)
            bindAbilitySection(section, exerciseId, records)
            container.addView(section)
        }
    }

    /** 운동 1개 섹션 — header(이름) + 좌우 스와이프 RecyclerView(record당 1페이지) + 페이지 인디케이터. */
    private fun bindAbilitySection(
        section: View,
        exerciseId: Int,
        records: List<ExerciseRecord>
    ) {
        val name = exerciseNames[exerciseId] ?: "운동 $exerciseId"
        section.findViewById<TextView>(R.id.tv_ex_name).text = name

        val tvEmpty = section.findViewById<TextView>(R.id.tv_empty)
        val rv = section.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_records)
        val tvPage = section.findViewById<TextView>(R.id.tv_page_indicator)

        if (records.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "운동 기록이 없습니다"
            rv.visibility = View.GONE
            tvPage.visibility = View.GONE
            return
        }

        tvEmpty.visibility = View.GONE
        rv.visibility = View.VISIBLE
        tvPage.visibility = View.VISIBLE

        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(),
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
            false
        )
        // 스와이프 시 1 페이지씩 스냅. 기존 SnapHelper가 있으면 제거 후 새로 부착(재바인딩 중복 방지).
        rv.onFlingListener = null
        androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(rv)

        rv.adapter = AbilityRecordAdapter(records, isBalance = exerciseId == 8)

        fun updatePageIndicator(currentPos: Int) {
            // records는 최신순(내림차순) — position 0 = 가장 최근.
            tvPage.text = "최신 ◀ ${currentPos + 1} / ${records.size} ▶ 오래된"
        }
        updatePageIndicator(0)

        rv.clearOnScrollListeners()
        rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                    val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
                        ?: lm.findFirstVisibleItemPosition()
                    if (pos >= 0) updatePageIndicator(pos)
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
