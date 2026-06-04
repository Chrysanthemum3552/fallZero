package com.fallzero.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fallzero.app.data.algorithm.ProgressionEvaluator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 보호자 공유용 보고서 이미지 생성. Canvas 직접 드로잉.
 * - 헤더: 사용자 이름·날짜·위험 등급
 * - 요약: 연속 운동일·오늘 완료율
 * - 8개 운동 카드: 각 게이트의 현재 값·필요 값·통과 여부(✓/✗)
 * - 진급까지 N회 필요 표시
 */
object GuardianReportRenderer {

    data class HeaderInfo(
        val userName: String,
        val date: String,
        val riskLevel: String,        // "위험군" / "비위험군" / "검사 필요"
        val isHighRisk: Boolean?,
        val streakDays: Int,
        val todayCompleted: Int,
        val todayTotal: Int
    )

    /** 운동 이행 현황 캘린더용 데이터. exercisedDays = 운동 완료한 날짜("yyyy-MM-dd", 로컬). */
    data class AdherenceInfo(
        val streakDays: Int,
        val weekCount: Int,
        val todayCompleted: Int,
        val todayTotal: Int,
        val exercisedDays: Set<String>
    )

    private const val WIDTH = 1080
    private const val PADDING = 40f
    private const val CARD_RADIUS = 24f
    private const val CARD_GAP = 24f
    private const val GATE_ROW_HEIGHT = 50f
    private const val CARD_HEADER_HEIGHT = 70f
    private const val CARD_FOOTER_HEIGHT = 60f
    private const val SECTION_GAP = 32f

    // 운동 이행 현황 캘린더(히트맵)
    private const val CAL_GAP = 14f
    private const val CAL_PAD = 28f
    private const val CAL_STATS_H = 120f
    private const val CAL_WEEKDAY_H = 50f
    private const val CAL_WEEKS = 4
    private const val COLOR_CAL_EMPTY = 0xFFE6E8EB.toInt()
    private const val COLOR_CAL_FUTURE = 0xFFF0F1F3.toInt()

    // 운동별 최근 5회 추이 (2열 미니 카드)
    private const val MINI_CELL_HEIGHT = 280f
    private const val MINI_CELL_GAP = 16f
    private const val SESSION_ROW_HEIGHT = 36f

    private const val COLOR_BG = 0xFFFFFFFF.toInt()
    private const val COLOR_CARD = 0xFFF7F8FA.toInt()
    private const val COLOR_TEXT_PRIMARY = 0xFF1A1A1A.toInt()
    private const val COLOR_TEXT_SECONDARY = 0xFF666666.toInt()
    private const val COLOR_PASS = 0xFF2E7D32.toInt()
    private const val COLOR_FAIL = 0xFFC62828.toInt()
    private const val COLOR_NEUTRAL = 0xFF9E9E9E.toInt()
    private const val COLOR_ACCENT = 0xFF1976D2.toInt()
    private const val COLOR_HIGH_RISK = 0xFFC62828.toInt()
    private const val COLOR_SAFE = 0xFF2E7D32.toInt()
    private const val COLOR_DIVIDER = 0xFFE0E0E0.toInt()

    /** 1페이지 — 운동 이행 현황(캘린더) + 훈련 진급 단계 + 운동별 최근 5회 추이. */
    fun renderPage1(
        header: HeaderInfo,
        adherence: AdherenceInfo,
        progressionLines: List<String>,
        evals: List<ProgressionEvaluator.Eval>
    ): Bitmap {
        val headerHeight = 280f
        val footerHeight = 80f
        val sectionTitleHeight = 60f
        val gridHeight = computeGridHeight(evals.size)
        val totalHeight = headerHeight +
            SECTION_GAP + sectionTitleHeight + 12f + adherenceCardHeight() +
            SECTION_GAP + sectionTitleHeight + 12f + infoCardHeight(progressionLines) +
            SECTION_GAP + sectionTitleHeight + 12f + gridHeight +
            SECTION_GAP + footerHeight

        val bitmap = Bitmap.createBitmap(WIDTH, totalHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BG)

        var y = PADDING
        y = drawHeader(canvas, y, header, "1 / 2")
        y += SECTION_GAP
        y = drawSectionTitle(canvas, y, "운동 이행 현황")
        y += 12f
        y = drawAdherenceCalendar(canvas, y, adherence)
        y += SECTION_GAP
        y = drawSectionTitle(canvas, y, "훈련 진급 단계")
        y += 12f
        y = drawInfoCard(canvas, y, progressionLines)
        y += SECTION_GAP
        y = drawSectionTitle(canvas, y, "운동별 최근 5회 추이")
        y += 12f
        y = drawTrendGrid(canvas, y, evals)
        y += SECTION_GAP
        drawFooter(canvas, y)
        return bitmap
    }

    /** 2페이지 — 운동별 능력 · 진급 현황 (게이트별 현재값·기준·통과 여부). */
    fun renderPage2(header: HeaderInfo, evals: List<ProgressionEvaluator.Eval>): Bitmap {
        val cardHeights = evals.map { cardHeight(it) }
        val headerHeight = 280f
        val footerHeight = 80f
        val sectionTitleHeight = 60f
        val totalHeight = headerHeight +
            SECTION_GAP + sectionTitleHeight + 12f +
            cardHeights.sumOf { it.toDouble() }.toFloat() +
            CARD_GAP * (evals.size - 1).coerceAtLeast(0) +
            SECTION_GAP + footerHeight

        val bitmap = Bitmap.createBitmap(WIDTH, totalHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BG)

        var y = PADDING
        y = drawHeader(canvas, y, header, "2 / 2")
        y += SECTION_GAP
        y = drawSectionTitle(canvas, y, "운동별 능력 · 진급 현황")
        y += 12f
        for ((index, eval) in evals.withIndex()) {
            y = drawExerciseCard(canvas, y, eval)
            if (index < evals.size - 1) y += CARD_GAP
        }
        y += SECTION_GAP
        drawFooter(canvas, y)
        return bitmap
    }

    /** 진급 조건 카드 높이 — 헤더 + 게이트 + 푸터. */
    private fun cardHeight(eval: ProgressionEvaluator.Eval): Float {
        val rows = if (eval.hasRecord) eval.gates.size else 1
        return CARD_HEADER_HEIGHT + rows * GATE_ROW_HEIGHT + CARD_FOOTER_HEIGHT
    }

    /** 2열 그리드: 운동 개수에 따라 행 수 계산. */
    private fun computeGridHeight(nExercises: Int): Float {
        val rows = (nExercises + 1) / 2
        return rows * MINI_CELL_HEIGHT + (rows - 1).coerceAtLeast(0) * MINI_CELL_GAP
    }

    /** 8개 운동을 2열 그리드 미니 카드로 표시 — 최근 5회 횟수 막대 + 자연어 평가. */
    private fun drawTrendGrid(canvas: Canvas, startY: Float, evals: List<ProgressionEvaluator.Eval>): Float {
        val cellWidth = (WIDTH - PADDING * 2 - MINI_CELL_GAP) / 2
        var y = startY
        var i = 0
        while (i < evals.size) {
            drawMiniTrendCell(canvas, PADDING, y, cellWidth, evals[i])
            if (i + 1 < evals.size) {
                drawMiniTrendCell(canvas, PADDING + cellWidth + MINI_CELL_GAP, y, cellWidth, evals[i + 1])
            }
            i += 2
            y += MINI_CELL_HEIGHT
            if (i < evals.size) y += MINI_CELL_GAP
        }
        return y
    }

    private fun drawMiniTrendCell(
        canvas: Canvas,
        x: Float,
        y: Float,
        cellWidth: Float,
        eval: ProgressionEvaluator.Eval
    ) {
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CARD }
        canvas.drawRoundRect(
            RectF(x, y, x + cellWidth, y + MINI_CELL_HEIGHT),
            CARD_RADIUS, CARD_RADIUS, cardPaint
        )

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(
            "${eval.exerciseId}. ${eval.exerciseName}",
            x + 16f, y + 36f, namePaint
        )

        if (!eval.hasRecord || eval.history.isEmpty()) {
            val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_NEUTRAL
                textSize = 22f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "아직 운동 기록이 없습니다",
                x + cellWidth / 2, y + MINI_CELL_HEIGHT / 2 + 6f,
                noDataPaint
            )
            return
        }

        val rowAreaTop = y + 52f
        for ((idx, pt) in eval.history.withIndex()) {
            drawSessionBarRow(
                canvas,
                x + 16f, rowAreaTop + idx * SESSION_ROW_HEIGHT,
                cellWidth - 32f, pt
            )
        }

        val summaryY = y + 52f + eval.history.size * SESSION_ROW_HEIGHT + 16f
        val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 22f
        }
        val summary = ProgressionEvaluator.summaryLine(eval)
        summaryPaint.color = when {
            eval.canProgress || eval.consecutiveDays >= 3 || eval.currentLevel >= eval.maxLevel -> COLOR_PASS
            eval.trend == ProgressionEvaluator.Trend.DECLINING -> COLOR_FAIL
            else -> COLOR_TEXT_PRIMARY
        }
        canvas.drawText(summary, x + 16f, summaryY + 20f, summaryPaint)
    }

    /** 1회 세션 가로 막대 — 날짜 + 막대(횟수 비율) + 횟수 텍스트. */
    private fun drawSessionBarRow(
        canvas: Canvas,
        x: Float, y: Float, width: Float,
        pt: ProgressionEvaluator.HistoryPoint
    ) {
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            textSize = 20f
        }
        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val rowCenterY = y + SESSION_ROW_HEIGHT / 2
        canvas.drawText(pt.dateLabel, x, rowCenterY + 7f, datePaint)

        val barLeft = x + 60f
        val barRight = x + width - 80f
        val barWidth = barRight - barLeft
        val barTop = rowCenterY - 10f
        val barBottom = rowCenterY + 10f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0E0.toInt() }
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 10f, 10f, trackPaint)

        val ratio = if (pt.targetCount > 0)
            (pt.achievedCount.toFloat() / pt.targetCount).coerceIn(0f, 1f)
        else 0f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (pt.passedAllGates) COLOR_PASS
                    else if (ratio >= 1f) 0xFFFF9800.toInt()
                    else COLOR_NEUTRAL
        }
        if (ratio > 0f) {
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barLeft + barWidth * ratio, barBottom),
                10f, 10f, fillPaint
            )
        }

        val countText = if (pt.isBalance) {
            if (pt.achievedCount >= pt.targetCount) "달성" else "미달성"
        } else {
            "${pt.achievedCount}/${pt.targetCount}회"
        }
        canvas.drawText(countText, x + width, rowCenterY + 7f, countPaint)
    }

    private fun drawHeader(canvas: Canvas, startY: Float, h: HeaderInfo, pageLabel: String? = null): Float {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            textSize = 30f
        }

        canvas.drawText("[낙상제로] 낙상 예방 운동 보고서", PADDING, startY + 50f, title)
        canvas.drawText("${h.userName} 어르신 · ${h.date}", PADDING, startY + 100f, subtitle)

        if (pageLabel != null) {
            val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_TEXT_SECONDARY
                textSize = 30f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(pageLabel, WIDTH - PADDING, startY + 50f, pagePaint)
        }

        val riskColor = when (h.isHighRisk) {
            true -> COLOR_HIGH_RISK
            false -> COLOR_SAFE
            null -> COLOR_NEUTRAL
        }
        val riskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = riskColor }
        val riskTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val riskBoxLeft = PADDING
        val riskBoxTop = startY + 130f
        val riskBoxRight = riskBoxLeft + 350f
        val riskBoxBottom = riskBoxTop + 70f
        canvas.drawRoundRect(
            RectF(riskBoxLeft, riskBoxTop, riskBoxRight, riskBoxBottom),
            16f, 16f, riskPaint
        )
        canvas.drawText(
            "낙상 위험: ${h.riskLevel}",
            (riskBoxLeft + riskBoxRight) / 2,
            riskBoxTop + 48f,
            riskTextPaint
        )

        return startY + 220f
    }

    private const val INFO_ROW_HEIGHT = 50f
    private const val INFO_CARD_PAD_V = 28f

    private fun infoCardHeight(lines: List<String>): Float =
        INFO_CARD_PAD_V * 2 + lines.size * INFO_ROW_HEIGHT

    /** 라벨 줄 목록을 담는 단순 카드 (운동 이행 현황 / 훈련 진급 단계). */
    private fun drawInfoCard(canvas: Canvas, startY: Float, lines: List<String>): Float {
        val height = infoCardHeight(lines)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CARD }
        canvas.drawRoundRect(
            RectF(PADDING, startY, WIDTH - PADDING, startY + height),
            CARD_RADIUS, CARD_RADIUS, cardPaint
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 30f
        }
        lines.forEachIndexed { i, line ->
            canvas.drawText(
                line,
                PADDING + 28f,
                startY + INFO_CARD_PAD_V + i * INFO_ROW_HEIGHT + 34f,
                textPaint
            )
        }
        return startY + height
    }

    private fun calCellSize(): Float {
        val usable = WIDTH - PADDING * 2 - CAL_PAD * 2
        return (usable - CAL_GAP * 6) / 7f
    }

    private fun adherenceCardHeight(): Float {
        val cell = calCellSize()
        val grid = CAL_WEEKS * cell + (CAL_WEEKS - 1) * CAL_GAP
        return CAL_PAD + CAL_STATS_H + 16f + CAL_WEEKDAY_H + grid + CAL_PAD
    }

    /** 운동 이행 현황 — 상단 통계 3칸 + 최근 4주 캘린더 히트맵(운동한 날 표시). */
    private fun drawAdherenceCalendar(canvas: Canvas, startY: Float, info: AdherenceInfo): Float {
        val height = adherenceCardHeight()
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CARD }
        canvas.drawRoundRect(
            RectF(PADDING, startY, WIDTH - PADDING, startY + height),
            CARD_RADIUS, CARD_RADIUS, cardPaint
        )

        val left = PADDING + CAL_PAD
        val innerW = WIDTH - PADDING * 2 - CAL_PAD * 2

        // 통계 3칸
        val statW = innerW / 3f
        drawStat(canvas, left, statW, startY + CAL_PAD, "연속 운동", "${info.streakDays}일")
        drawStat(canvas, left + statW, statW, startY + CAL_PAD, "이번 주", "${info.weekCount}회")
        drawStat(canvas, left + statW * 2, statW, startY + CAL_PAD, "오늘", "${info.todayCompleted}/${info.todayTotal}")

        // 요일 헤더
        val cell = calCellSize()
        val weekdayY = startY + CAL_PAD + CAL_STATS_H + 16f
        val weekdays = listOf("일", "월", "화", "수", "목", "금", "토")
        val wdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; textAlign = Paint.Align.CENTER }
        for (c in 0..6) {
            wdPaint.color = when (c) { 0 -> COLOR_FAIL; 6 -> COLOR_ACCENT; else -> COLOR_TEXT_SECONDARY }
            canvas.drawText(weekdays[c], left + c * (cell + CAL_GAP) + cell / 2, weekdayY + 30f, wdPaint)
        }

        // 날짜 셀 (이번 주 일요일 기준 4주 전부터)
        val gridTop = weekdayY + CAL_WEEKDAY_H
        val cal = Calendar.getInstance()
        val today = Date(cal.timeInMillis)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val todayStr = sdf.format(today)
        val todayDow = cal.get(Calendar.DAY_OF_WEEK) - 1   // 0=일
        cal.add(Calendar.DAY_OF_MONTH, -(todayDow + (CAL_WEEKS - 1) * 7))

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f; color = COLOR_ACCENT
        }
        for (w in 0 until CAL_WEEKS) {
            for (d in 0..6) {
                val ds = sdf.format(cal.time)
                val dayNum = cal.get(Calendar.DAY_OF_MONTH)
                val isFuture = cal.time.after(today)
                val exercised = info.exercisedDays.contains(ds)
                val isToday = ds == todayStr
                val cx = left + d * (cell + CAL_GAP)
                val cy = gridTop + w * (cell + CAL_GAP)
                val rect = RectF(cx, cy, cx + cell, cy + cell)
                cellPaint.color = when {
                    exercised -> COLOR_PASS
                    isFuture -> COLOR_CAL_FUTURE
                    else -> COLOR_CAL_EMPTY
                }
                canvas.drawRoundRect(rect, 16f, 16f, cellPaint)
                if (isToday) canvas.drawRoundRect(rect, 16f, 16f, ringPaint)
                numPaint.color = when {
                    exercised -> Color.WHITE
                    isFuture -> 0xFFBDBDBD.toInt()
                    else -> COLOR_TEXT_SECONDARY
                }
                canvas.drawText("$dayNum", cx + cell / 2, cy + cell / 2 + 11f, numPaint)
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return startY + height
    }

    private fun drawStat(canvas: Canvas, x: Float, w: Float, top: Float, label: String, value: String) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY; textSize = 28f; textAlign = Paint.Align.CENTER
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY; textSize = 56f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val cx = x + w / 2
        canvas.drawText(label, cx, top + 34f, labelPaint)
        canvas.drawText(value, cx, top + 96f, valuePaint)
    }

    private fun drawSectionTitle(canvas: Canvas, y: Float, text: String): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(text, PADDING, y + 40f, paint)
        return y + 50f
    }

    private fun drawExerciseCard(canvas: Canvas, startY: Float, eval: ProgressionEvaluator.Eval): Float {
        val height = cardHeight(eval)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CARD }
        canvas.drawRoundRect(
            RectF(PADDING, startY, WIDTH - PADDING, startY + height),
            CARD_RADIUS, CARD_RADIUS, cardPaint
        )

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // 운동명: 좌측, 진급 단계: 같은 라인 우측
        canvas.drawText("${eval.exerciseId}. ${eval.exerciseName}", PADDING + 24f, startY + 50f, titlePaint)
        val levelText = if (eval.exerciseId == 8) {
            "현재 ${eval.currentLevel}단계 / 최종 ${eval.maxLevel}단계"
        } else {
            "현재 ${eval.currentLevel}세트 / 최종 ${eval.maxLevel}세트"
        }
        val rightLevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACCENT
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(levelText, WIDTH - PADDING - 24f, startY + 50f, rightLevelPaint)

        if (!eval.hasRecord) {
            val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_NEUTRAL
                textSize = 30f
            }
            canvas.drawText(
                "아직 운동 기록이 없습니다",
                PADDING + 24f,
                startY + CARD_HEADER_HEIGHT + 36f,
                noDataPaint
            )
            return startY + height
        }

        val gateY = startY + CARD_HEADER_HEIGHT
        for ((index, gate) in eval.gates.withIndex()) {
            drawGateRow(canvas, gateY + index * GATE_ROW_HEIGHT, gate)
        }

        val footerY = gateY + eval.gates.size * GATE_ROW_HEIGHT
        drawProgressionFooter(canvas, footerY, eval)
        return startY + height
    }

    private fun drawGateRow(canvas: Canvas, y: Float, gate: ProgressionEvaluator.Gate) {
        val iconColor = if (gate.passed) COLOR_PASS else COLOR_FAIL
        val icon = if (gate.passed) "✓" else "✗"

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 28f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val reqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            textSize = 22f
            textAlign = Paint.Align.RIGHT
        }

        val rowCenterBaseline = y + GATE_ROW_HEIGHT / 2 + 10f
        canvas.drawText(icon, PADDING + 24f, rowCenterBaseline, iconPaint)
        canvas.drawText(gate.label, PADDING + 80f, rowCenterBaseline, labelPaint)

        // 값 + 기준을 단일 라인으로 표시 (겹침 방지). 값은 강조 색·굵게, 기준은 회색 작은 폰트.
        // 오른쪽 끝에서부터 거꾸로 그림: 기준 → 구분점 → 값
        val rightEdge = WIDTH - PADDING - 24f
        val reqText = " · ${gate.requirementText}"
        canvas.drawText(reqText, rightEdge, rowCenterBaseline, reqPaint)
        val reqWidth = reqPaint.measureText(reqText)
        canvas.drawText(gate.currentText, rightEdge - reqWidth, rowCenterBaseline, valuePaint)

        val dividerPaint = Paint().apply {
            color = COLOR_DIVIDER
            strokeWidth = 1f
        }
        canvas.drawLine(
            PADDING + 24f, y + GATE_ROW_HEIGHT - 1f,
            WIDTH - PADDING - 24f, y + GATE_ROW_HEIGHT - 1f,
            dividerPaint
        )
    }

    private fun drawProgressionFooter(canvas: Canvas, y: Float, eval: ProgressionEvaluator.Eval) {
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f }
        val daysPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            textSize = 26f
            textAlign = Paint.Align.RIGHT
        }
        val centerY = y + CARD_FOOTER_HEIGHT / 2 + 12f

        when {
            eval.currentLevel >= eval.maxLevel -> {
                statusPaint.color = COLOR_PASS
                statusPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("✓ 최종 단계 달성", PADDING + 24f, centerY, statusPaint)
            }
            eval.canProgress -> {
                statusPaint.color = COLOR_PASS
                statusPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("✓ 진급 가능", PADDING + 24f, centerY, statusPaint)
            }
            else -> {
                statusPaint.color = COLOR_TEXT_PRIMARY
                statusPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                val remain = (eval.daysNeeded - eval.consecutiveDays).coerceAtLeast(0)
                canvas.drawText(
                    if (remain == 0) "이번 회차에 모두 통과해야 진급" else "진급까지 ${remain}일 더 필요",
                    PADDING + 24f, centerY, statusPaint
                )
            }
        }
        canvas.drawText(
            "연속 통과: ${eval.consecutiveDays} / ${eval.daysNeeded} 회",
            WIDTH - PADDING - 24f, centerY, daysPaint
        )
    }

    private fun drawFooter(canvas: Canvas, y: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("낙상제로 (FallZero) — 자동 생성 보고서", WIDTH / 2f, y + 40f, paint)
    }
}
