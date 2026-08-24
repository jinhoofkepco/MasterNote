package com.studyink.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.studyink.core.model.StudentActivitySample
import com.studyink.core.model.peakInkLength
import kotlin.math.roundToInt

/**
 * Ten second buckets of a student's writing while the teacher is monitoring live.
 *
 * The bar height is how far the pen travelled in that bucket. The lighter portion on top is ink
 * that looked like colouring in rather than writing - a hint to look at the page, never a verdict.
 */
@Composable
fun StudentActivityDialog(
    samples: List<StudentActivitySample>,
    role: ReaderRole,
    onDismiss: () -> Unit,
) {
    val tokens = readerChromeTokens(role)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(tokens.cornerRadius),
            color = tokens.buttonBackground,
            contentColor = tokens.buttonForeground,
            shadowElevation = tokens.hoveredElevation,
        ) {
            Column(
                modifier = Modifier.padding(18.dp).width(320.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("학생 필기량", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = summaryLine(samples),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.statusForeground,
                )
                ActivityBars(samples = samples, tokens = tokens)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegendSwatch(tokens.paletteBlue, "필기", tokens)
                    LegendSwatch(tokens.paletteOrange, "색칠로 보임", tokens)
                }
                Text(
                    text = "10초마다 한 칸입니다. 색칠 판정은 참고용이며, 실제 페이지를 함께 확인해 주세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.statusForeground,
                )
                Text(
                    text = "닫기",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(tokens.cornerRadius))
                        .background(tokens.actionBackground)
                        .padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                    color = tokens.actionForeground,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ActivityBars(samples: List<StudentActivitySample>, tokens: ReaderChromeTokens) {
    if (samples.isEmpty()) {
        Text(
            text = "아직 기록이 없습니다. 10초 뒤 첫 칸이 생깁니다.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.statusForeground,
        )
        return
    }
    val peak = samples.peakInkLength()
    val writingColour = tokens.paletteBlue
    val fillColour = tokens.paletteOrange
    val baseline = tokens.outline.copy(alpha = 0.4f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(tokens.statusBackground),
    ) {
        val slot = size.width / samples.size
        val barWidth = (slot * 0.66f).coerceAtLeast(1f)
        val floor = size.height - 1f
        drawLine(
            color = baseline,
            start = Offset(0f, floor),
            end = Offset(size.width, floor),
            strokeWidth = 1f,
        )
        samples.forEachIndexed { index, sample ->
            val height = (sample.inkLength / peak).coerceIn(0f, 1f) * (size.height - 8f)
            if (height <= 0f) return@forEachIndexed
            val left = index * slot + (slot - barWidth) / 2f
            val fillHeight = height * sample.fillRatio
            drawRect(
                color = writingColour,
                topLeft = Offset(left, floor - height),
                size = Size(barWidth, height - fillHeight),
            )
            if (fillHeight > 0f) {
                drawRect(
                    color = fillColour,
                    topLeft = Offset(left, floor - fillHeight),
                    size = Size(barWidth, fillHeight),
                )
            }
        }
    }
}

@Composable
private fun LegendSwatch(colour: Color, label: String, tokens: ReaderChromeTokens) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.width(10.dp).height(10.dp)) {
            drawRect(colour, size = size)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tokens.statusForeground)
    }
}

internal fun summaryLine(samples: List<StudentActivitySample>): String {
    if (samples.isEmpty()) return "관찰 시작 대기 중"
    val minutes = samples.size * 10 / 60
    val seconds = samples.size * 10 % 60
    val writingBuckets = samples.count { !it.isIdle }
    val fill = samples.sumOf { (it.inkLength * it.fillRatio).toDouble() }
    val total = samples.sumOf { it.inkLength.toDouble() }
    val fillPercent = if (total <= 0.0) 0 else (fill / total * 100).roundToInt()
    val span = if (minutes > 0) "${minutes}분 ${seconds}초" else "${seconds}초"
    return "관찰 $span · 필기한 구간 $writingBuckets/${samples.size} · 색칠로 보인 비율 $fillPercent%"
}
