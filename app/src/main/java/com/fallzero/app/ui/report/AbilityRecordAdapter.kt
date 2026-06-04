package com.fallzero.app.ui.report

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * 운동 1개의 최근 N개 기록을 좌우 스와이프로 보여주는 어댑터. 페이지 = 1 record.
 *
 * 각 회(rep)를 동그라미로 표시: 🟢 초록 = 자세 오류 없이 수행, 🟠 주황 = 자세 오류(+짧은 사유).
 *   · 비양방(#4~#7): 동그라미 한 줄(10개)
 *   · 양방(#1~#3): 좌/우 두 줄(각 10개)
 *   · 균형(#8): 좌/우 유지시간(초)
 *   · repResults가 비어있는(이 기능 이전) 기록: "상세 없음"으로 흐리게
 *
 * repResults 포맷은 ExerciseRecord 주석 참고.
 */
class AbilityRecordAdapter(
    private val records: List<ExerciseRecord>,
    private val exerciseId: Int
) : RecyclerView.Adapter<AbilityRecordAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREAN)
    private val isBilateral = exerciseId in setOf(1, 2, 3)  // #8은 초로 별도 표시
    private val perSideTarget = 10

    private val orangeColor = 0xFFFFA726.toInt()
    private val emptyColor = 0xFF555555.toInt()

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
        val ctx = holder.itemView.context
        val record = records[position]
        val tvPromoted = holder.itemView.findViewById<TextView>(R.id.tv_promoted)
        val tvDate = holder.itemView.findViewById<TextView>(R.id.tv_date)
        val container = holder.itemView.findViewById<LinearLayout>(R.id.container_rows)
        val tvReasons = holder.itemView.findViewById<TextView>(R.id.tv_reasons)

        // 진급 발생 회차 배지
        if (record.promotedLabel.isNotEmpty()) {
            tvPromoted.visibility = View.VISIBLE
            tvPromoted.text = "🎉 ${record.promotedLabel}"
        } else {
            tvPromoted.visibility = View.GONE
        }

        tvDate.text = dateFormat.format(Date(record.performedAt))
        tvDate.alpha = 1f
        container.removeAllViews()

        val rr = record.repResults

        // 이 기능 이전 기록 — 동그라미 정보 없음 → 흐리게
        if (rr.isBlank()) {
            tvDate.alpha = 0.5f
            val tv = TextView(ctx).apply {
                text = "이전 기록 — 상세(동그라미) 정보 없음"
                textSize = 14f
                alpha = 0.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            container.addView(tv)
            tvReasons.visibility = View.GONE
            return
        }

        // 균형(#8) — 좌/우 유지시간(초)
        if (exerciseId == 8 && rr.startsWith("B")) {
            val map = parseKeyVals(rr)
            val tv = TextView(ctx).apply {
                text = "왼발 ${map["L"] ?: "0"}초   ·   오른발 ${map["R"] ?: "0"}초"
                textSize = 20f
                setTextColor(ContextCompat.getColor(ctx, R.color.success))
            }
            container.addView(tv)
            tvReasons.visibility = View.GONE
            return
        }

        // 근력 — 회당 동그라미
        val map = parseKeyVals(rr)
        val rows: List<Pair<String?, List<String?>>> = if (isBilateral) {
            listOf("왼쪽" to parseMarks(map["L"]), "오른쪽" to parseMarks(map["R"]))
        } else {
            listOf(null to parseMarks(map["S"]))
        }

        var green = 0
        var orange = 0
        val reasonParts = mutableListOf<String>()
        for ((label, marks) in rows) {
            container.addView(buildCircleRow(ctx, label, marks))
            marks.forEachIndexed { i, m ->
                if (m == null) {
                    green++
                } else {
                    orange++
                    val side = if (label != null) "$label " else ""
                    reasonParts.add("$side${i + 1}회 $m")
                }
            }
        }
        tvReasons.visibility = View.VISIBLE
        tvReasons.text = if (reasonParts.isEmpty()) {
            "🟢 초록 $green · 🟠 주황 0    모두 정확히 수행했어요!"
        } else {
            "🟢 초록 $green · 🟠 주황 $orange\n지적: ${reasonParts.joinToString(" · ")}"
        }
    }

    /** "M;L=O|상체 기울임;R=O|O" → {L:"O|상체 기울임", R:"O|O"} */
    private fun parseKeyVals(rr: String): Map<String, String> =
        rr.split(";").drop(1).mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else it.substring(0, idx) to it.substring(idx + 1)
        }.toMap()

    /** "O|상체 기울임|O" → [null, "상체 기울임", null]. null = 초록. */
    private fun parseMarks(s: String?): List<String?> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.split("|").map { if (it == "O") null else it }
    }

    private fun buildCircleRow(ctx: Context, label: String?, marks: List<String?>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(ctx, 6) }
        }
        if (label != null) {
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        for (i in 0 until perSideTarget) {
            val color = when {
                i >= marks.size -> emptyColor                                   // 미수행(패딩)
                marks[i] == null -> ContextCompat.getColor(ctx, R.color.success) // 초록
                else -> orangeColor                                             // 주황
            }
            row.addView(circleView(ctx, color))
        }
        return row
    }

    private fun circleView(ctx: Context, color: Int): View {
        val size = dp(ctx, 18)
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = dp(ctx, 4) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
