package com.fallzero.app.ui.report

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fallzero.app.R
import com.fallzero.app.data.db.entity.ExerciseRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 운동 1개의 최근 N개 기록을 좌우 스와이프로 보여주는 어댑터 — 단순 진급 룰에 맞춰 재작성.
 * 각 페이지(=record) 안에는 R1~Rn 셀이 그리드로 표시:
 *   초록 ✓ = 그 반복 안에서 음성 안내가 한 번도 나오지 않음(잘 수행)
 *   주황 ! = 그 반복 안에서 음성 안내(자세 교정·코칭)가 발생
 *   어두운 칸 = 목표 횟수에 도달하기 전 종료된 반복(미수행)
 *
 * @param isBalance 운동 #8 (한 발 서기). true면 셀 1개를 큰 카드 형태로 표시 + "유지" 라벨.
 */
class AbilityRecordAdapter(
    private val records: List<ExerciseRecord>,
    private val isBalance: Boolean
) : RecyclerView.Adapter<AbilityRecordAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREAN)

    class VH(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ability_record, parent, false)
        // 페이지가 RecyclerView 전체 폭을 차지하도록 강제 — PagerSnapHelper 스냅용.
        view.layoutParams = view.layoutParams.apply { width = parent.measuredWidth }
        return VH(view)
    }

    override fun getItemCount(): Int = records.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = records[position]
        val ctx = holder.itemView.context

        val flags = parseFlags(record.repFeedbackFlags)
        val target = record.targetCount.coerceAtLeast(1)
        // 표시 셀 개수: 목표 횟수만큼. 도달한 반복(flags)은 색상으로, 그 외는 미수행(어두움).
        val cellCount = target

        val passed = flags.isNotEmpty() && record.achievedCount >= record.targetCount &&
                flags.all { !it }

        val tvSummary = holder.itemView.findViewById<TextView>(R.id.tv_summary)
        val tvDate = holder.itemView.findViewById<TextView>(R.id.tv_date)
        val tvHint = holder.itemView.findViewById<TextView>(R.id.tv_progression_hint)
        val container = holder.itemView.findViewById<LinearLayout>(R.id.layout_reps)

        tvDate.text = dateFormat.format(Date(record.performedAt))

        when {
            flags.isEmpty() -> {
                tvSummary.text = "측정 데이터가 부족해요"
                tvSummary.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            record.achievedCount < record.targetCount -> {
                tvSummary.text = "${record.achievedCount}/${record.targetCount}회 수행"
                tvSummary.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
            }
            passed -> {
                tvSummary.text = "잘 따라하셨어요"
                tvSummary.setTextColor(ContextCompat.getColor(ctx, R.color.success))
            }
            else -> {
                val n = flags.count { it }
                tvSummary.text = "안내 ${n}회 — 다시 도전해봐요"
                tvSummary.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
            }
        }

        tvHint.text = when {
            flags.isEmpty() -> "운동 기록을 다시 측정해주세요."
            passed -> if (isBalance)
                "안내 없이 끝까지 잘 버텼어요 — 다음 단계로 진급!"
            else
                "모든 반복에서 안내 없이 잘 수행했어요 — 다음 세트로 진급!"
            record.achievedCount < record.targetCount ->
                "목표 횟수를 끝까지 채우면 진급해요. 다음에 다시 도전해봐요."
            else -> {
                val n = flags.count { it }
                "안내가 ${n}회 발생했어요. 다음에 모두 안내 없이 해내면 진급해요."
            }
        }
        tvHint.setTextColor(ContextCompat.getColor(ctx,
            if (passed) R.color.success else R.color.text_secondary))

        buildRepGrid(container, flags, cellCount)
    }

    private fun parseFlags(csv: String): List<Boolean> =
        csv.split(",").filter { it.isNotBlank() }.map { it == "1" }

    /**
     * R1~Rn 셀을 5열 그리드로 동적 배치. flags 길이가 cellCount보다 짧으면 나머지는 "미수행" 셀.
     * 균형 운동(isBalance)은 1셀이라도 5열 폭을 다 쓰지 않고 한 칸만 차지하도록 동일 로직.
     */
    private fun buildRepGrid(container: LinearLayout, flags: List<Boolean>, cellCount: Int) {
        container.removeAllViews()
        if (cellCount <= 0) return
        val ctx = container.context
        val colsPerRow = if (isBalance) 1 else 5
        val rows = (cellCount + colsPerRow - 1) / colsPerRow
        val cellMargin = dp(ctx, 4f).toInt()
        val cellHeight = dp(ctx, 56f).toInt()

        var repIndex = 0
        repeat(rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (col in 0 until colsPerRow) {
                if (repIndex >= cellCount) {
                    // 행을 채우기 위한 invisible spacer (그리드 정렬 유지).
                    row.addView(spacer(ctx, cellMargin, cellHeight))
                    continue
                }
                val state: CellState = when {
                    repIndex >= flags.size -> CellState.EMPTY
                    flags[repIndex] -> CellState.FEEDBACK
                    else -> CellState.GOOD
                }
                row.addView(buildRepCell(ctx, repIndex + 1, state, cellMargin, cellHeight))
                repIndex++
            }
            container.addView(row)
        }
    }

    private enum class CellState { GOOD, FEEDBACK, EMPTY }

    private fun buildRepCell(
        ctx: Context, repNumber: Int, state: CellState,
        margin: Int, height: Int
    ): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.setMargins(margin, margin, margin, margin) }
        }

        val cell = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height
            )
            background = ContextCompat.getDrawable(ctx, when (state) {
                CellState.GOOD -> R.drawable.bg_rep_cell_good
                CellState.FEEDBACK -> R.drawable.bg_rep_cell_feedback
                CellState.EMPTY -> R.drawable.bg_rep_cell_empty
            })
        }

        val symbol = TextView(ctx).apply {
            text = when (state) {
                CellState.GOOD -> "✓"
                CellState.FEEDBACK -> "!"
                CellState.EMPTY -> ""
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            // 흰색 글자가 초록/주황 배경 위에서 가장 잘 보임. 노인 친화 명도 확보.
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        }
        cell.addView(symbol)

        val label = TextView(ctx).apply {
            text = "R$repNumber"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 4f).toInt() }
        }

        wrapper.addView(cell)
        wrapper.addView(label)
        return wrapper
    }

    private fun spacer(ctx: Context, margin: Int, height: Int): View {
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, height, 1f)
                .also { it.setMargins(margin, margin, margin, margin) }
        }
    }

    private fun dp(ctx: Context, value: Float): Float =
        value * ctx.resources.displayMetrics.density
}