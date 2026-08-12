package com.studyink.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.studyink.core.model.AnswerVerdict
import com.studyink.core.model.ReviewDecision
import com.studyink.core.model.ReviewPageCheckStatus
import com.studyink.core.model.ReviewSession
import com.studyink.core.model.ReviewStatus

@Composable
internal fun TeacherReviewSupportingPane(
    session: ReviewSession?,
    currentPage: Int,
    summary: String,
    layerSources: List<ReaderLayerSource>,
    layerVisibility: List<Boolean>,
    publishing: Boolean,
    error: String?,
    onSummaryChange: (String) -> Unit,
    onToggleLayer: (Int, Boolean) -> Unit,
    onTogglePageChecked: (Boolean) -> Unit,
    onEvaluate: (String, AnswerVerdict) -> Unit,
    onPublish: (ReviewDecision) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFFAFAFD), tonalElevation = 4.dp) {
        if (session == null) {
            Text(error ?: "검토 정보를 불러오는 중…", Modifier.padding(20.dp))
            return@Surface
        }
        val draft = session.review.status == ReviewStatus.DRAFT
        val page = session.pages.firstOrNull { it.pageNumber == currentPage }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${session.attempt.attemptNumber}번째 제출 검토", style = MaterialTheme.typography.titleMedium)
            Text("페이지 ${currentPage + 1} / ${session.pages.size}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = page?.checkStatus == ReviewPageCheckStatus.CHECKED,
                    onCheckedChange = if (draft) onTogglePageChecked else null,
                )
                Text("현재 페이지 확인")
            }
            HorizontalDivider()
            Text("레이어", style = MaterialTheme.typography.labelLarge)
            layerSources.forEachIndexed { index, source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = layerVisibility.getOrElse(index) { source.visibleByDefault },
                        onCheckedChange = { onToggleLayer(index, it) },
                    )
                    Text(layerName(source))
                }
            }
            if (session.submission.answers.isNotEmpty()) {
                HorizontalDivider()
                Text("구조화 답안", style = MaterialTheme.typography.labelLarge)
                session.submission.answers.forEach { answer ->
                    Text("${answer.fieldId}: ${answer.valueJson}", style = MaterialTheme.typography.bodySmall)
                    if (draft) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEvaluate(answer.fieldId, AnswerVerdict.CORRECT) }) { Text("정답") }
                        TextButton(onClick = { onEvaluate(answer.fieldId, AnswerVerdict.PARTIAL) }) { Text("부분") }
                        TextButton(onClick = { onEvaluate(answer.fieldId, AnswerVerdict.INCORRECT) }) { Text("오답") }
                    }
                }
            }
            OutlinedTextField(
                value = summary,
                onValueChange = onSummaryChange,
                enabled = draft,
                label = { Text("검토 메모") },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (draft) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPublish(ReviewDecision.ACCEPTED) },
                        enabled = !publishing,
                        modifier = Modifier.weight(1f),
                    ) { Text("완료") }
                    Button(
                        onClick = { onPublish(ReviewDecision.RETRY_REQUESTED) },
                        enabled = !publishing,
                        modifier = Modifier.weight(1f),
                    ) { Text("다시 풀기") }
                }
            } else {
                Text("게시됨 · ${session.review.decision}", color = Color(0xFF16845B))
            }
        }
    }
}

private fun layerName(source: ReaderLayerSource): String = when (source) {
    is EditableLiveLayer -> "현재 선생 첨삭"
    is ReadOnlyLiveLayer -> "선생 사전 설명"
    is ReadOnlySnapshot -> when (source.target) {
        is SnapshotTarget.StudentSubmission -> "학생 제출 필기"
        is SnapshotTarget.PublishedReview -> "게시된 첨삭"
    }
}
