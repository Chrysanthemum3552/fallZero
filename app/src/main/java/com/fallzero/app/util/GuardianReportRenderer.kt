package com.fallzero.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fallzero.app.data.algorithm.ProgressionEvaluator

/**
 * 보호자 공유용 보고서 이미지 생성. Canvas 직접 드로잉.
 * - 헤더: 사용자 이름·날짜·위험 등급
 * - 요약: 연속 운동일·오늘 완료율
 * - 8개 운동 카드: 각 게이트의 현재 값·필요 값·통과 여부(✓/✗)
 * - 진급까지 N일 필요 표시
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

    private const val WIDTH = 1080
    private const val PADDING = 40f
    private const val CARD_RADIUS = 24f
    private const val CARD_GAP = 24f
    private const val GATE_ROW_HEIGHT = 50f
    private const val CARD_HEADER_HEIGHT = 70f
    private const val CARD_FOOTER_HEIGHT = 60f
    private const val SECTION_GAP = 32f
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

    fun render(header: HeaderInfo, evals: List<ProgressionEvaluator.Eval>): Bitmap {
        val cardHeights = evals.map { cardHeight(it) }
        val headerHeight = 280f
        val summaryHeight = 140f
        val footerHeight = 80f
        val gridHeight = computeGridHeight(evals.size)
        val sectionTitleHeight = 60f
        val totalHeight = headerHeight + summaryHeight +
            SECTION_GAP + sectionTitleHeight + gridHeight +
            SECTION_GAP + sectionTitleHeight +
            cardHeights.sumOf { it.toDouble() }.toFloat() +
            CARD_GAP * (evals.size - 1).coerceAtLeast(0) +
            SECTION_GAP * 2 +
            footerHeight

        val bitmap = Bitmap.createBitmap(WIDTH, totalHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BG)

        var y = PADDING
        y = drawHeader(canvas, y, header)
        y += SECTION_GAP
        y = drawSummary(canvas, y, header)
        y += SECTION_GAP
        y = drawSectionTitle(canvas, y, "운동 능력 추이 (최근 5회)")
        y += 12f
        y = drawTrendGrid(canvas, y, evals)
        y += SECTION_GAP
        y = drawSectionTitle(canvas, y, "운동별 진급 조건")
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

    private fun drawHeader(canvas: Canvas, startY: Float, h: HeaderInfo): Float {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            textSize = 30f
        }

        canvas.drawText("[낙상제로] 운동 진척도 보고서", PADDING, startY + 50f, title)
        canvas.drawText("${h.userName} 어르신 · ${h.date}", PADDING, startY + 100f, subtitle)

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

    private fun drawSummary(canvas: Canvas, startY: Float, h: HeaderInfo): Float {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            textSize = 28f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 60f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CARD }

        val cardWidth = (WIDTH - PADDING * 2 - CARD_GAP) / 2
        val cardHeight = 130f

        canvas.drawRoundRect(
            RectF(PADDING, startY, PADDING + cardWidth, startY + cardHeight),
            CARD_RADIUS, CARD_RADIUS, cardPaint
        )
        canvas.drawText("연속 운동", PADDING + 24f, startY + 40f, labelPaint)
        canvas.drawText("${h.streakDays} 일", PADDING + 24f, startY + 105f, valuePaint)

        val rightX = PADDING + cardWidth + CARD_GAP
        canvas.drawRoundRect(
            RectF(rightX, startY, rightX + cardWidth, startY + cardHeight),
            CARD_RADIUS, CARD_RADIUS, cardPaint
        )
        canvas.drawText("오늘 운동", rightX + 24f, startY + 40f, labelPaint)
        val todayValueColor = if (h.todayCompleted >= h.todayTotal) COLOR_PASS else COLOR_TEXT_PRIMARY
        valuePaint.color = todayValueColor
        canvas.drawText("${h.todayCompleted} / ${h.todayTotal}", rightX + 24f, startY + 105f, valuePaint)
        valuePaint.color = COLOR_TEXT_PRIMARY

        return startY + cardHeight
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

    /** 섹션 A — 8개 운동을 2열 그리드에 미니 카드로 표시. 각 카드는 추세 + 바 차트만 담음. */
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

    /** 미니 운동 카드 — 운동명 + 최근 5회 횟수 막대 + 자연어 평가.
     *  근력 운동: 가로 막대 + "8/10회" 표시. 균형 운동: ✓/✗ 아이콘 + "달성/미달성". */
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

        // 5개 세션 가로 막대
        val rowAreaTop = y + 52f
        for ((idx, pt) in eval.history.withIndex()) {
            drawSessionBarRow(
                canvas,
                x + 16f, rowAreaTop + idx * SESSION_ROW_HEIGHT,
                cellWidth - 32f, pt
            )
        }

        // 카드 바닥 자연어 요약
        val summaryY = y + 52f + eval.history.size * SESSION_ROW_HEIGHT + 16f
        val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 22f
        }
        val summary = ProgressionEvaluator.summaryLine(eval)
        // 요약 색상: 진급 가능/연속 달성이면 초록, 하락이면 빨강
        summaryPaint.color = when {
            eval.canProgress || eval.consecutiveDays >= 3 || eval.currentLevel >= eval.maxLevel -> COLOR_PASS
            eval.trend == ProgressionEvaluator.Trend.DECLINING -> COLOR_FAIL
            else -> COLOR_TEXT_PRIMARY
        }
        canvas.drawText(summary, x + 16f, summaryY + 20f, summaryPaint)
    }

    /** 1회 세션 가로 막대 — 날짜 + 막대(횟수 비율) + 횟수 텍스트 + 체크. */
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
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_PASS
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val rowCenterY = y + SESSION_ROW_HEIGHT / 2

        // 날짜 (좌측)
        canvas.drawText(pt.dateLabel, x, rowCenterY + 7f, datePaint)

        // 막대 영역
        val barLeft = x + 60f
        val barRight = x + width - 100f
        val barWidth = barRight - barLeft
        val barTop = rowCenterY - 10f
        val barBottom = rowCenterY + 10f

        // 배경 막대 (회색 트랙)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0E0.toInt() }
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 10f, 10f, trackPaint)

        // 채움 막대
        val ratio = if (pt.targetCount > 0)
            (pt.achievedCount.toFloat() / pt.targetCount).coerceIn(0f, 1f)
        else 0f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (pt.passedAllGates) COLOR_PASS
                    else if (ratio >= 1f) 0xFFFF9800.toInt()  // 목표 도달했지만 게이트 일부 미달 — 주황
                    else COLOR_NEUTRAL
        }
        if (ratio > 0f) {
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barLeft + barWidth * ratio, barBottom),
                10f, 10f, fillPaint
            )
        }

        // 횟수 텍스트 (우측)
        val isBalance = pt.isBalance
        val countText = if (isBalance) {
            if (pt.achievedCount >= pt.targetCount) "달성" else "미달성"
        } else {
            "${pt.achievedCount}/${pt.targetCount}회"
        }
        canvas.drawText(countText, x + width - 24f, rowCenterY + 7f, countPaint)
        if (pt.passedAllGates) {
            canvas.drawText("✓", x + width, rowCenterY + 8f, checkPaint)
        }
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
            "연속 통과: ${eval.consecutiveDays} / ${eval.daysNeeded} 일",
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
