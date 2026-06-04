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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.databinding.FragmentReportBinding
import kotlinx.coroutines.launch

/**
 * 운동 기록 화면 — 운동별 "회당 동그라미"만 표시.
 * 🟢 정확히 수행 / 🟠 지적받음(+사유). 양방은 좌/우 2줄, 균형(#8)은 유지시간(초).
 * 운동당 최근 5회를 좌우 스와이프로 확인.
 */
class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

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
        loadAbilityCards()
    }

    /** 8개 운동 각각 카드 1장 — 카드 안에서 좌우 스와이프로 최근 5개 record를 동그라미로 확인. */
    private fun loadAbilityCards() {
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
            val userId = prefs.getInt("user_id", 0)
            val db = FallZeroDatabase.getInstance(requireContext())

            val b = _binding ?: return@launch
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
    }

    /** 운동 1개 섹션 — header(이름) + 좌우 스와이프 RecyclerView(record당 1페이지) + 페이지 인디케이터. */
    private fun bindAbilitySection(
        section: View,
        exerciseId: Int,
        records: List<ExerciseRecord>
    ) {
        section.findViewById<TextView>(R.id.tv_ex_name).text = SessionFlow.exerciseName(exerciseId)

        val tvEmpty = section.findViewById<TextView>(R.id.tv_empty)
        val rv = section.findViewById<RecyclerView>(R.id.rv_records)
        val tvPage = section.findViewById<TextView>(R.id.tv_page_indicator)

        if (records.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "⚪ 아직 한 번도 안 해 보셨어요"
            rv.visibility = View.GONE
            tvPage.visibility = View.GONE
            return
        }

        tvEmpty.visibility = View.GONE
        rv.visibility = View.VISIBLE
        tvPage.visibility = View.VISIBLE

        rv.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )
        // 스와이프 시 1 페이지씩 스냅 (재바인딩 시 중복 부착 방지)
        rv.onFlingListener = null
        PagerSnapHelper().attachToRecyclerView(rv)

        rv.adapter = AbilityRecordAdapter(records, exerciseId)

        fun updatePageIndicator(currentPos: Int) {
            // records는 최신순(내림차순)이라 position 0 = 가장 최근.
            tvPage.text = "최신 ◀ ${currentPos + 1} / ${records.size} ▶ 오래된"
        }
        updatePageIndicator(0)

        rv.clearOnScrollListeners()
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
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
