package com.fallzero.app.ui.report

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fallzero.app.R
import com.fallzero.app.data.db.entity.ExerciseRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 운동 1개의 최근 N개 기록을 좌우 스와이프로 보여주는 어댑터.
 * 페이지 = 1 record. width는 onBind에서 RecyclerView 폭으로 강제 설정.
 *
 * @param baselineMs 시간 효율 점수용 — null이면 시간 점수 100점 기본.
 * @param isBalance  운동 #8 (한 발 서기). true면 ROM/일관성 슬롯 라벨이 안정성/유지시간으로.
 */
class AbilityRecordAdapter(
    private val records: List<ExerciseRecord>,
    private val baselineMs: Float?,
    private val isBalance: Boolean
) : RecyclerView.Adapter<AbilityRecordAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREAN)

    class VH(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ability_record, parent, false)
        // 페이지가 RecyclerView 전체 폭을 차지하도록 강제 — PagerSnapHelper 스냅용
        view.layoutParams = view.layoutParams.apply { width = parent.measuredWidth }
        return VH(view)
    }

    override fun getItemCount(): Int = records.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = records[position]
        val ctx = holder.itemView.context

        val tvScore = holder.itemView.findViewById<TextView>(R.id.tv_score)
        val tvGradeDate = holder.itemView.findViewById<TextView>(R.id.tv_grade_date)

        val total = record.qualityScore
        val grade = when {
            total >= 85 -> "🟢 잘하고 있어요"
            total >= 65 -> "🟡 보통이에요"
            else -> "🔴 노력 필요"
        }
        val dateStr = dateFormat.format(Date(record.performedAt))
        tvScore.text = "${total}점"
        tvScore.setTextColor(scoreColor(ctx, total))
        tvGradeDate.text = "$grade\n$dateStr"

        val speedScore = ((1f - record.speedLossRate) * 100f).toInt().coerceIn(0, 100)
        val timeScore = computeTimeScore(record.durationMs, baselineMs)

        bindRow(ctx, holder.itemView.findViewById(R.id.row_completion),
            "달성도", record.completionScore)
        bindRow(ctx, holder.itemView.findViewById(R.id.row_form),
            "자세 정확도", record.formScore)
        bindRow(ctx, holder.itemView.findViewById(R.id.row_rom),
            if (isBalance) "안정성" else "ROM 활용도", record.romScore)
        bindRow(ctx, holder.itemView.findViewById(R.id.row_consistency),
            if (isBalance) "유지시간" else "일관성", record.consistencyScore)
        bindRow(ctx, holder.itemView.findViewById(R.id.row_speed),
            "속도 유지력", speedScore)
        bindRow(ctx, holder.itemView.findViewById(R.id.row_time),
            "시간 효율", timeScore)
    }

    private fun bindRow(ctx: android.content.Context, row: View, label: String, score: Int) {
        row.findViewById<TextView>(R.id.tv_label).text = label
        row.findViewById<TextView>(R.id.tv_value).text = score.toString()
        row.findViewById<TextView>(R.id.tv_value).setTextColor(scoreColor(ctx, score))
        row.findViewById<ProgressBar>(R.id.pb_value).progress = score
    }

    /** QualityScorer.calcTimeEfficiency와 동일 식. */
    private fun computeTimeScore(durationMs: Long, baselineMs: Float?): Int {
        if (baselineMs == null || baselineMs <= 0f || durationMs <= 0L) return 100
        val ratio = durationMs.toFloat() / baselineMs
        if (ratio <= 1.0f) return 100
        return (100f - (ratio - 1.0f) * 80f).toInt().coerceIn(0, 100)
    }

    private fun scoreColor(ctx: android.content.Context, score: Int): Int = when {
        score >= 85 -> ContextCompat.getColor(ctx, R.color.success)
        score >= 65 -> ContextCompat.getColor(ctx, R.color.text_primary)
        else -> ContextCompat.getColor(ctx, R.color.error)
    }
}
