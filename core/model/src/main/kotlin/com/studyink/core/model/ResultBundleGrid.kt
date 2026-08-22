package com.studyink.core.model

import kotlin.math.ceil
import kotlin.math.ln

/** One stable position in a compact page/problem result mosaic. */
data class ResultBundleCell(
    /** May end in .5 when the final incomplete row is centered. */
    val column: Float,
    val row: Int,
)

/**
 * Shared geometry for the reader chrome, marks on paper, and library page cards.
 *
 * The approved visual uses narrow 3:5 cells with a one-unit gap. Eight results therefore form a
 * 4 x 2 mosaic. Other counts are scored by physical outer aspect and unused slots so every screen
 * keeps the same compact cell ordering and shape.
 */
data class ResultBundleGrid(
    val columns: Int,
    val rows: Int,
    val cells: List<ResultBundleCell>,
)

fun resultBundleGrid(itemCount: Int): ResultBundleGrid {
    require(itemCount >= 0)
    if (itemCount == 0) return ResultBundleGrid(0, 0, emptyList())

    val cellWidth = 3f
    val cellHeight = 5f
    val gap = 1f
    var bestColumns = 1
    var bestRows = itemCount
    var bestScore = Float.POSITIVE_INFINITY

    for (columns in 1..itemCount) {
        val rows = ceil(itemCount.toDouble() / columns).toInt()
        if (columns < rows) continue
        val width = columns * cellWidth + (columns - 1) * gap
        val height = rows * cellHeight + (rows - 1) * gap
        val aspectPenalty = kotlin.math.abs(ln((width / height).toDouble())).toFloat()
        val emptyFraction = (columns * rows - itemCount).toFloat() / (columns * rows)
        val score = aspectPenalty + emptyFraction * 0.75f
        if (score < bestScore) {
            bestScore = score
            bestColumns = columns
            bestRows = rows
        }
    }

    val cells = buildList(itemCount) {
        for (row in 0 until bestRows) {
            val itemsOnRow = minOf(bestColumns, itemCount - row * bestColumns)
            val centeredStart = (bestColumns - itemsOnRow) / 2f
            repeat(itemsOnRow) { column ->
                add(ResultBundleCell(centeredStart + column, row))
            }
        }
    }
    return ResultBundleGrid(bestColumns, bestRows, cells)
}
