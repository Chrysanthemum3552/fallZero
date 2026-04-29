package com.fallzero.app.ui.report

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.databinding.FragmentReportBinding
import com.fallzero.app.viewmodel.ReportViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보고서 Page 1 - 검사 결과 요약 + 최근 세션 운동 결과
 * 담당: 송민석
 */
class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReportViewModel by activityViewModels()

    // 운동 ID -> 이름 매핑
    private val exerciseNames = mapOf(
        1 to "앉아서 무릎 펴기",
        2 to "옆으로 다리 들기",
        3 to "뒤로 무릎 굽히기",
        4 to "발뒤꿈치 들기",
        5 to "발끝 들기",
        6 to "무릎 살짝 굽히기",
        7 to "의자에서 일어서기",
        8 to "한 발로 서기"
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

        binding.btnNextPage.setOnClickListener {
            findNavController().navigate(R.id.action_report_to_page2)
        }

        // 검사 결과 관찰
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.page1.collect { data ->
                    val b = _binding ?: return@collect
                    data ?: return@collect
                    val latest = data.latestResult
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)

                    if (latest == null) {
                        b.tvLatestRisk.text = getString(R.string.report_no_data)
                        return@collect
                    }
                    val isHighRisk = latest.finalRiskLevel == "high"
                    b.tvLatestRisk.text = if (isHighRisk) "낙상 위험군" else "낙상 안전군"
                    b.tvLatestRisk.setBackgroundResource(
                        if (isHighRisk) R.drawable.bg_risk_level else R.drawable.bg_risk_low
                    )
                    b.tvChairDetail.text =
                        "의자 일어서기: ${latest.chairStandCount}회 (기준 ${latest.chairStandNorm}회)" +
                                "\n검사일: ${dateFormat.format(Date(latest.performedAt))}"
                    b.tvBalanceDetail.text =
                        "균형 검사: ${latest.balanceStageReached}단계 · 탠덤 ${latest.tandemTimeSec.toInt()}초"
                    val first = data.firstResult
                    b.tvImprovement.text = when {
                        first == null || first.id == latest.id -> "첫 번째 검사 결과입니다."
                        data.isImproved -> "이전 검사 대비 위험군에서 안전군으로 개선되었습니다!"
                        latest.finalRiskLevel == first.finalRiskLevel -> "이전 검사와 동일한 판정입니다."
                        else -> "이전 검사 대비 지속적으로 모니터링이 필요합니다."
                    }
                }
            }
        }

        // 최근 세션 운동 결과 로드
        loadRecentSessionResults()
    }

    private fun loadRecentSessionResults() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            // 가장 최근 완료 세션 1개
            val recentSessions = db.sessionDao().getRecentCompletedSessions(userId, 1)
            val b = _binding ?: return@launch

            if (recentSessions.isEmpty()) {
                b.tvSessionTitle.text = "아직 운동 기록이 없어요"
                b.tvSessionAvg.visibility = View.GONE
                return@launch
            }

            val session = recentSessions.first()
            val records = db.sessionDao().getRecordsBySession(session.id)

            if (records.isEmpty()) {
                b.tvSessionTitle.text = "운동 기록이 없어요"
                b.tvSessionAvg.visibility = View.GONE
                return@launch
            }

            // 세션 날짜
            val dateStr = SimpleDateFormat("MM월 dd일", Locale.KOREAN)
                .format(Date(session.startedAt))
            b.tvSessionTitle.text = "${dateStr} 세션"

            // 평균 점수 계산
            val avgScore = records.map { it.qualityScore }.average().toInt()
            b.tvSessionAvg.visibility = View.VISIBLE
            b.tvSessionAvg.text = "평균 품질 점수: ${avgScore}점"

            // 이전 세션과 비교
            val prevSessions = db.sessionDao().getRecentCompletedSessions(userId, 2)
            if (prevSessions.size >= 2) {
                val prevRecords = db.sessionDao().getRecordsBySession(prevSessions[1].id)
                if (prevRecords.isNotEmpty()) {
                    val prevAvg = prevRecords.map { it.qualityScore }.average().toInt()
                    val diff = avgScore - prevAvg
                    b.tvSessionCompare.visibility = View.VISIBLE
                    b.tvSessionCompare.text = when {
                        diff > 0 -> "지난 세션(${prevAvg}점)보다 ${diff}점 올랐어요!"
                        diff < 0 -> "지난 세션(${prevAvg}점)보다 ${-diff}점 줄었어요"
                        else -> "지난 세션과 점수가 같아요"
                    }
                    b.tvSessionCompare.setTextColor(
                        if (diff >= 0) ContextCompat.getColor(requireContext(), R.color.success)
                        else ContextCompat.getColor(requireContext(), R.color.warning)
                    )
                }
            } else {
                b.tvSessionCompare.visibility = View.GONE
            }

            // 운동별 카드 동적 생성
            b.layoutExerciseCards.removeAllViews()
            records.sortedBy { it.exerciseId }.forEach { record ->
                val cardView = createExerciseCard(record)
                b.layoutExerciseCards.addView(cardView)
            }
        }
    }

    /**
     * 운동별 결과 카드 동적 생성
     */
    private fun createExerciseCard(record: ExerciseRecord): View {
        val ctx = requireContext()
        val name = exerciseNames[record.exerciseId] ?: "운동 ${record.exerciseId}"
        val score = record.qualityScore
        val scoreColor = when {
            score >= 80 -> ContextCompat.getColor(ctx, R.color.primary)
            score >= 60 -> ContextCompat.getColor(ctx, R.color.warning)
            else -> ContextCompat.getColor(ctx, R.color.error)
        }

        // 카드 컨테이너
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 36)
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            layoutParams = lp
        }

        // 운동명 + 총점 행
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvName = TextView(ctx).apply {
            text = name
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvScore = TextView(ctx).apply {
            text = "${score}점"
            textSize = 16f
            setTextColor(scoreColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(tvName)
        headerRow.addView(tvScore)
        card.addView(headerRow)

        // 프로그레스바
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = score
            progressTintList = android.content.res.ColorStateList.valueOf(scoreColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 20
            ).apply { setMargins(0, 16, 0, 16) }
        }
        card.addView(progressBar)

        // 세부 점수 태그 행
        val tagRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        listOf(
            "달성 ${record.completionScore}" to "#E3F2FD",
            "자세 ${record.formScore}" to "#E8F5E9",
            "ROM ${record.romScore}" to "#FFF3E0",
            "일관성 ${record.consistencyScore}" to "#F3E5F5"
        ).forEach { (label, bgColor) ->
            val tag = TextView(ctx).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor(bgColor).darken())
                setPadding(16, 8, 16, 8)
                setBackgroundColor(Color.parseColor(bgColor))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 12, 0) }
                layoutParams = lp
            }
            tagRow.addView(tag)
        }
        card.addView(tagRow)

        return card
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// Int 색상을 어둡게 만드는 확장함수 (태그 텍스트용)
private fun Int.darken(): Int {
    val r = (android.graphics.Color.red(this) * 0.5f).toInt()
    val g = (android.graphics.Color.green(this) * 0.5f).toInt()
    val b = (android.graphics.Color.blue(this) * 0.5f).toInt()
    return android.graphics.Color.rgb(r, g, b)
}

// Color.parseColor 편의 임포트용
private object Color {
    fun parseColor(hex: String): Int = android.graphics.Color.parseColor(hex)
}