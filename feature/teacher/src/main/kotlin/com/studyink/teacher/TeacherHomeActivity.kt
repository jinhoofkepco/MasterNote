package com.studyink.teacher

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyink.core.model.ReviewQueueItem
import com.studyink.core.model.TeacherQueueStatus
import com.studyink.reader.ReaderActivity
import com.studyink.reader.ReaderSceneIntentCodec

class TeacherHomeActivity : androidx.activity.ComponentActivity() {
    private val viewModel: TeacherHomeViewModel by viewModels()
    private val reader = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TeacherSession.controller.isValid()) {
            startActivity(Intent(this, TeacherModeGateActivity::class.java))
            finish()
            return
        }
        TeacherSession.controller.enteredForeground()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                viewModel.readerScenes.collect { scene ->
                    reader.launch(ReaderSceneIntentCodec.put(Intent(this@TeacherHomeActivity, ReaderActivity::class.java), scene))
                }
            }
            TeacherHomeScreen(state, viewModel::openQueueItem, viewModel::openPreparation) {
                TeacherSession.controller.invalidate()
                finish()
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) TeacherSession.controller.enteredBackground()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        TeacherSession.controller.enteredForeground()
    }
}

@Composable
private fun TeacherHomeScreen(
    state: TeacherHomeUiState,
    openReview: (ReviewQueueItem) -> Unit,
    openPreparation: () -> Unit,
    exitTeacherMode: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("선생 모드", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = exitTeacherMode) { Text("학생 모드") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = openPreparation) { Text("교재 준비") }
                Button(onClick = { context.startActivity(Intent(context, AnswerSetupActivity::class.java)) }) { Text("정답지 설정") }
                Button(onClick = {
                    context.startActivity(com.studyink.remote.feature.RemoteSessionActivity.intent(
                        context, com.studyink.remote.session.RemoteSessionRole.TEACHER,
                    ))
                }) { Text("원격 수업") }
            }
            Text("제출 검토", style = MaterialTheme.typography.titleMedium)
            when (state) {
                TeacherHomeUiState.Loading -> Text("불러오는 중…", Modifier.padding(20.dp))
                is TeacherHomeUiState.Error -> Text(state.message, Modifier.padding(20.dp))
                is TeacherHomeUiState.Content -> LazyColumn {
                    items(state.queue, key = { it.submissionId.value }) { item ->
                        Column(
                            Modifier.fillMaxWidth().clickable { openReview(item) }.padding(vertical = 14.dp)
                        ) {
                            Text("${item.learnerName} · ${item.activityTitle} · ${item.attemptNumber}번째 풀이", fontWeight = FontWeight.Medium)
                            Text("${item.bookTitle} · ${queueLabel(item.status)}", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun queueLabel(status: TeacherQueueStatus) = when (status) {
    TeacherQueueStatus.UNREVIEWED -> "검토 전"
    TeacherQueueStatus.IN_REVIEW -> "검토 중"
    TeacherQueueStatus.REVIEWED_ACCEPTED -> "완료"
    TeacherQueueStatus.REVIEWED_RETRY -> "다시 풀기 요청"
}
