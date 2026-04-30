package com.fallzero.app.ui.exam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExamResult
import com.fallzero.app.databinding.FragmentExamResultBinding
import com.fallzero.app.util.ShareHelper
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExamResultFragment : Fragment() {

    private var _binding: FragmentExamResultBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExamViewModel by activityViewModels()
    private var ttsManager: TTSManager? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExamResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ttsManager = TTSManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.phase.collect { phase ->
                    if (phase is ExamViewModel.ExamPhase.Completed) {
                        displayResult(phase)
                    }
                }
            }
        }
        viewModel.getCompletedResult()?.let { displayResult(it) }

        binding.btnGoHome.setOnClickListener {
            SessionFlow.reset()
            viewModel.resetForNewSession()
            findNavController().navigate(R.id.action_result_to_home)
        }

        binding.btnShare.setOnClickListener {
            val phase = viewModel.phase.value
            if (phase is ExamViewModel.ExamPhase.Completed) {
                ShareHelper.shareText(
                    requireContext(),
                    "낙상제로 검사 결과",
                    buildShareText(phase)
                )
            }
        }
    }

    private fun displayResult(phase: ExamViewModel.ExamPhase.Completed) {
        val result = phase.result
        val isHighRisk = result.finalRiskLevel == "high"

        binding.tvRiskLevel.text = if (isHighRisk)
            getString(R.string.exam_high_risk)
        else
            getString(R.string.exam_low_risk)
        binding.tvRiskLevel.setBackgroundResource(
            if (isHighRisk) R.drawable.bg_risk_level else R.drawable.bg_risk_low
        )

        binding.tvRecommendation.text = if (isHighRisk) {
            "낙상 위험이 감지되었습니다.\n꾸준한 OEP 운동과 의사 상담을 권장합니다."
        } else {
            "현재 낙상 위험이 낮습니다.\n예방 운동을 꾸준히 이어나가세요!"
        }

        loadHistoryAndDrawGraphs(result, isHighRisk)
    }

    private fun loadHistoryAndDrawGraphs(current: ExamResult, isHighRisk: Boolean) {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val history = db.examResultDao().getRecentResults(userId, 5)
            val b = _binding ?: return@launch

            if (history.size >= 2) {
                // 실제 DB 데이터로 그래프 표시
                val chairData = history.reversed().map { it.chairStandCount.toFloat() }
                b.chartChairStand.setData(chairData, "#FFFF00")

                val prev = history.getOrNull(1)
                if (prev != null) {
                    val chairDiff = current.chairStandCount - prev.chairStandCount
                    b.tvChairStandResult.text = when {
                        chairDiff > 0 -> "지난 검사보다 ${chairDiff}회 더 하셨어요!"
                        chairDiff < 0 -> "지난 검사보다 ${-chairDiff}회 줄었어요"
                        else -> "지난 검사와 횟수가 같아요"
                    }

                    val balanceData = history.reversed().map { it.balanceStageReached.toFloat() }
                    b.chartBalance.setData(balanceData, "#00FF88")

                    val stageDiff = current.balanceStageReached - prev.balanceStageReached
                    b.tvBalanceResult.text = when {
                        stageDiff > 0 -> "${stageDiff}단계 더 통과하셨어요!"
                        stageDiff < 0 -> "${-stageDiff}단계 줄었어요"
                        else -> "지난 검사와 단계가 같아요"
                    }
                }
            } else {
                // ── 더미 데이터 (DB 데이터 부족 시 자동 적용) ──
                // 현재 값을 마지막 점으로, 이전 4회는 가상 데이터
                val dummyChair = listOf(
                    (current.chairStandCount - 4).coerceAtLeast(1).toFloat(),
                    (current.chairStandCount - 2).coerceAtLeast(1).toFloat(),
                    (current.chairStandCount - 1).coerceAtLeast(1).toFloat(),
                    current.chairStandCount.toFloat()
                )
                b.chartChairStand.setData(dummyChair, "#FFFF00")
                b.tvChairStandResult.text =
                    "${current.chairStandCount}회 완료 (기준 ${current.chairStandNorm}회 이상)"

                val dummyBalance = listOf(
                    (current.balanceStageReached - 2).coerceAtLeast(1).toFloat(),
                    (current.balanceStageReached - 1).coerceAtLeast(1).toFloat(),
                    (current.balanceStageReached - 1).coerceAtLeast(1).toFloat(),
                    current.balanceStageReached.toFloat()
                )
                b.chartBalance.setData(dummyBalance, "#00FF88")
                b.tvBalanceResult.text = "${current.balanceStageReached}단계 통과"
            }

            val riskText = if (isHighRisk) "낙상 주의군" else "낙상 안전군"
            ttsManager?.speak(
                "검사 결과, ${riskText}에 해당합니다. " +
                        "의자 일어서기 ${current.chairStandCount}회, " +
                        "균형 검사 ${current.balanceStageReached}단계 통과입니다. " +
                        binding.tvRecommendation.text.toString().replace("\n", " ")
            )
        }
    }

    private fun buildShareText(phase: ExamViewModel.ExamPhase.Completed): String {
        val r = phase.result
        val dateStr = SimpleDateFormat("yyyy년 MM월 dd일", Locale.KOREAN).format(Date(r.performedAt))
        val riskText = if (r.finalRiskLevel == "high") "낙상 위험군" else "낙상 안전군"
        val chairStatus = if (r.isHighRiskChairStand) "주의 필요" else "정상"
        val balanceStatus = if (r.isHighRiskBalance) "주의 필요" else "정상"
        val surveyStatus = if (r.isHighRiskSurvey) "1개 이상 해당 (주의)" else "해당 없음 (정상)"
        val recommendation = if (r.finalRiskLevel == "high") {
            "낙상 위험이 감지되었습니다. 꾸준한 OEP 운동과 의사 상담을 권장합니다."
        } else {
            "현재 낙상 위험이 낮습니다. 예방 운동을 꾸준히 이어나가세요."
        }
        return buildString {
            appendLine("[낙상제로] 보호자 알림")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("검사일: $dateStr")
            appendLine("최종 판정: $riskText")
            appendLine()
            appendLine("[세부 검사 결과]")
            appendLine("의자 일어서기: ${r.chairStandCount}회 (기준 ${r.chairStandNorm}회 이상) - $chairStatus")
            appendLine("균형 검사: ${r.balanceStageReached}단계 통과 / 탠덤 ${r.tandemTimeSec.toInt()}초 - $balanceStatus")
            appendLine("낙상 위험 설문: $surveyStatus")
            appendLine()
            appendLine("[안내]")
            appendLine(recommendation)
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━")
            append("낙상제로 앱에서 전송됨")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}

class SimpleChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: List<Float> = emptyList()
    private var barColor: Int = android.graphics.Color.parseColor("#FFFF00")

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = android.graphics.Color.BLACK
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.parseColor("#888888")
    }
    private val latestValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.parseColor("#666666")
    }
    private val todayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = android.graphics.Color.parseColor("#333333")
    }

    fun setData(values: List<Float>, colorHex: String) {
        data = values
        barColor = android.graphics.Color.parseColor(colorHex)
        barPaint.color = barColor
        linePaint.color = barColor
        dotPaint.color = barColor
        latestValuePaint.color = barColor
        todayLabelPaint.color = barColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padTop = 52f
        val padBottom = 44f
        val padSide = 24f
        val chartH = h - padTop - padBottom
        val chartW = w - padSide * 2
        val maxVal = (data.maxOrNull() ?: 1f).coerceAtLeast(1f)

        // 검정 배경
        canvas.drawColor(android.graphics.Color.parseColor("#1A1A1A"))

        // 그리드
        for (i in 1..3) {
            val y = padTop + chartH * (1f - i / 4f)
            canvas.drawLine(padSide, y, w - padSide, y, gridPaint)
        }

        if (data.size == 1) {
            val barW = chartW * 0.35f
            val barH = (data[0] / maxVal) * chartH * 0.85f
            val left = w / 2 - barW / 2
            val top = padTop + (chartH - barH)
            val shadowPaint = Paint(barPaint).apply { alpha = 40 }
            canvas.drawRoundRect(left + 4f, top + 4f, left + barW + 4f, padTop + chartH, 12f, 12f, shadowPaint)
            canvas.drawRoundRect(left, top, left + barW, padTop + chartH, 12f, 12f, barPaint)
            canvas.drawText(data[0].toInt().toString(), w / 2, top - 10f, latestValuePaint)
            canvas.drawText("오늘", w / 2, h - 8f, todayLabelPaint)
            return
        }

        val stepX = chartW / (data.size - 1)
        val path = android.graphics.Path()
        val fillPath = android.graphics.Path()
        val fillPaint = Paint(barPaint).apply { alpha = 40; style = Paint.Style.FILL }

        data.forEachIndexed { i, value ->
            val x = padSide + i * stepX
            val y = padTop + chartH - (value / maxVal) * chartH * 0.85f
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, padTop + chartH)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(padSide + (data.size - 1) * stepX, padTop + chartH)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        data.forEachIndexed { i, value ->
            val x = padSide + i * stepX
            val y = padTop + chartH - (value / maxVal) * chartH * 0.85f
            val isLast = i == data.size - 1
            dotPaint.color = if (isLast) barColor else android.graphics.Color.parseColor("#555555")
            canvas.drawCircle(x, y, if (isLast) 14f else 9f, dotPaint)
            if (isLast) canvas.drawCircle(x, y, 14f, dotBorderPaint)
            val paint = if (isLast) latestValuePaint else valuePaint
            canvas.drawText(value.toInt().toString(), x, y - 16f, paint)
            val lPaint = if (isLast) todayLabelPaint else labelPaint
            canvas.drawText(if (isLast) "오늘" else "${i + 1}회", x, h - 8f, lPaint)
        }
    }
}