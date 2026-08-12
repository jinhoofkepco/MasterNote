package com.studyink.progress

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyink.reader.ReaderActivity
import com.studyink.core.model.ActivityProgressState

class ProgressActivity : ComponentActivity() {
    private val viewModel: ProgressViewModel by viewModels()
    private val reader = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Room's progress Flow invalidates after submission; no manual counter is maintained here.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                viewModel.readerLaunches.collect { args ->
                    reader.launch(args.putInto(Intent(this@ProgressActivity, ReaderActivity::class.java)))
                }
            }
            ProgressScreen(state, viewModel::openActivity, viewModel::retry)
        }
    }
}

@Composable
fun ProgressScreen(
    state: ProgressUiState,
    onActivityClick: (com.studyink.core.model.LearningActivityId) -> Unit,
    onRetry: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F6FA)) {
            when (state) {
                ProgressUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is ProgressUiState.Error -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.message)
                    if (state.retryAllowed) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRetry) { Text("다시 시도") }
                    }
                }
                is ProgressUiState.Content -> Column(Modifier.fillMaxSize()) {
                    Text(
                        text = state.bookTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                    )
                    HorizontalDivider(color = Color(0xFFE0E3EB))
                    LazyColumn {
                        items(state.activities, key = { it.activityId.value }) { activity ->
                            ActivityProgressRow(activity) { onActivityClick(activity.activityId) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityProgressRow(activity: ActivityProgressUi, onClick: () -> Unit) {
    val progressDescription = buildString {
        append("제출 ${activity.markerCount}회")
        if (activity.hasDraftMarker) append(", 풀이 중")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = activity.enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(activity.title, fontWeight = FontWeight.Medium)
            Text(
                text = when (activity.state) {
                    ActivityProgressState.NOT_STARTED -> "시작 전"
                    ActivityProgressState.IN_PROGRESS -> "풀이 중"
                    ActivityProgressState.SUBMITTED -> "검토 대기"
                    ActivityProgressState.RETRY_REQUIRED -> "다시 풀기"
                    ActivityProgressState.COMPLETED -> "완료"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (activity.state) {
                    ActivityProgressState.RETRY_REQUIRED -> Color(0xFFD14343)
                    ActivityProgressState.COMPLETED -> Color(0xFF16845B)
                    else -> Color(0xFF73798A)
                },
            )
        }
        Row(
            modifier = Modifier.semantics { contentDescription = progressDescription },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(activity.markerCount) { ProgressMarker(filled = true) }
            if (activity.hasDraftMarker) ProgressMarker(filled = false)
        }
    }
    HorizontalDivider(color = Color(0xFFE7E9EF))
}

@Composable
private fun ProgressMarker(filled: Boolean) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(15.dp)
            .clip(shape)
            .border(2.dp, Color(0xFF17233C), shape)
            .background(if (filled) Color(0xFF17233C) else Color.Transparent)
    )
}
