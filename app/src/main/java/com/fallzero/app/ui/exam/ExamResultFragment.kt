package com.fallzero.app.ui.exam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
        ttsManager = TTSManager.getInstance(requireContext())

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

        // 판정 표시
        binding.tvRiskLevel.text = if (isHighRisk)
            getString(R.string.exam_high_risk)
        else
            getString(R.string.exam_low_risk)
        binding.tvRiskLevel.setBackgroundResource(
            if (isHighRisk) R.drawable.bg_risk_level else R.drawable.bg_risk_low
        )

        binding.tvRecommendation.text = if (isHighRisk)
            "낙상 위험이 감지되었습니다. 꾸준한 OEP 운동과 의사 상담을 권장합니다."
        else
            "현재 낙상 위험이 낮습니다. 예방 운동을 꾸준히 이어나가세요!"

        // 원형 그래프 - 의자 일어서기 (사용자 횟수 / 기준 횟수)
        val chairColor = if (result.isHighRiskChairStand) "#FF9800" else "#FFFF00"
        binding.chartChairStand.setData(
            user = result.chairStandCount.toFloat(),
            max = result.chairStandNorm.toFloat().coerceAtLeast(1f),
            color = chairColor,
            valueText = "${result.chairStandCount}회",
            normText = "기준 ${result.chairStandNorm}회"
        )
        binding.tvChairStandResult.text = if (result.isHighRiskChairStand)
            "위험 신호 있음"
        else
            "안전"

        // 원형 그래프 - 균형 검사 (일렬 시간 / 10초)
        val balanceColor = if (result.isHighRiskBalance) "#FF9800" else "#00FF88"
        binding.chartBalance.setData(
            user = result.tandemTimeSec,
            max = 10f,
            color = balanceColor,
            valueText = "${result.tandemTimeSec.toInt()}초",
            normText = "기준 10초"
        )
        binding.tvBalanceResult.text = if (result.isHighRiskBalance)
            "위험 신호 있음"
        else
            "안전"

        // TTS
        val riskText = if (isHighRisk) "낙상 주의군" else "낙상 안전군"
        ttsManager?.speak(
            "검사 결과, ${riskText}에 해당합니다. " +
                    "의자 일어서기 ${result.chairStandCount}회, " +
                    "균형 검사 ${result.tandemTimeSec.toInt()}초입니다. " +
                    binding.tvRecommendation.text.toString().replace("\n", " ")
        )
    }

    private fun buildShareText(phase: ExamViewModel.ExamPhase.Completed): String {
        val r = phase.result
        val dateStr = SimpleDateFormat("yyyy년 MM월 dd일", Locale.KOREAN).format(Date(r.performedAt))
        val riskText = if (r.finalRiskLevel == "high") "낙상 위험군" else "낙상 안전군"
        val chairStatus = if (r.isHighRiskChairStand) "주의 필요" else "정상"
        val balanceStatus = if (r.isHighRiskBalance) "주의 필요" else "정상"
        val surveyStatus = if (r.isHighRiskSurvey) "1개 이상 해당 (주의)" else "해당 없음 (정상)"
        val recommendation = if (r.finalRiskLevel == "high")
            "낙상 위험이 감지되었습니다. 꾸준한 OEP 운동과 의사 상담을 권장합니다."
        else
            "현재 낙상 위험이 낮습니다. 예방 운동을 꾸준히 이어나가세요."
        return buildString {
            appendLine("[낙상제로] 보호자 알림")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("검사일: $dateStr")
            appendLine("최종 판정: $riskText")
            appendLine()
            appendLine("[세부 검사 결과]")
            appendLine("의자 일어서기: ${r.chairStandCount}회 (기준 ${r.chairStandNorm}회 이상) - $chairStatus")
            appendLine("균형 검사: ${r.balanceStageReached}단계 통과 / 일렬 ${r.tandemTimeSec.toInt()}초 - $balanceStatus")
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

/** 원형 진행률 그래프 (검사 결과 화면 전용) */
class CircularChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var userValue = 0f
    private var maxValue = 10f
    private var progressColor = android.graphics.Color.YELLOW
    private var valueText = "0"
    private var normText = ""

    private val STROKE_WIDTH = 44f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        color = android.graphics.Color.parseColor("#333333")
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 52f
    }
    private val normPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f
        color = android.graphics.Color.parseColor("#888888")
    }

    fun setData(user: Float, max: Float, color: String, valueText: String, normText: String) {
        userValue = user
        maxValue = max.coerceAtLeast(1f)
        progressColor = android.graphics.Color.parseColor(color)
        this.valueText = valueText
        this.normText = normText
        progressPaint.color = progressColor
        valuePaint.color = progressColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = STROKE_WIDTH + 12f
        val oval = RectF(pad, pad, w - pad, h - pad)

        // 배경 원
        canvas.drawArc(oval, -90f, 360f, false, bgPaint)

        // 진행 호
        val ratio = (userValue / maxValue).coerceIn(0f, 1f)
        val sweep = 360f * ratio
        if (sweep > 0f) canvas.drawArc(oval, -90f, sweep, false, progressPaint)

        // 중앙 값 텍스트
        val cx = w / 2f
        val textBlockH = valuePaint.textSize + 10f + normPaint.textSize
        val startY = (h - textBlockH) / 2f + valuePaint.textSize

        canvas.drawText(valueText, cx, startY, valuePaint)
        canvas.drawText(normText, cx, startY + normPaint.textSize + 6f, normPaint)
    }
}

/** 꺾은선 그래프 (운동 보고서 화면에서 사용) */
class SimpleChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: List<Float> = emptyList()
    private var barColor: Int = android.graphics.Color.parseColor("#FFFF00")

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
        val padTop = 52f; val padBottom = 44f; val padSide = 24f
        val chartH = h - padTop - padBottom
        val chartW = w - padSide * 2
        val maxVal = (data.maxOrNull() ?: 1f).coerceAtLeast(1f)

        canvas.drawColor(android.graphics.Color.parseColor("#1A1A1A"))

        for (i in 1..3) {
            val y = padTop + chartH * (1f - i / 4f)
            canvas.drawLine(padSide, y, w - padSide, y, gridPaint)
        }

        if (data.size == 1) {
            val barW = chartW * 0.35f
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = barColor
            }
            val barH = (data[0] / maxVal) * chartH * 0.85f
            val left = w / 2 - barW / 2
            val top = padTop + (chartH - barH)
            canvas.drawRoundRect(left, top, left + barW, padTop + chartH, 12f, 12f, barPaint)
            canvas.drawText(data[0].toInt().toString(), w / 2, top - 10f, latestValuePaint)
            canvas.drawText("오늘", w / 2, h - 8f, todayLabelPaint)
            return
        }

        val stepX = chartW / (data.size - 1)
        val path = android.graphics.Path()
        val fillPath = android.graphics.Path()
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = barColor; alpha = 40; style = Paint.Style.FILL
        }

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