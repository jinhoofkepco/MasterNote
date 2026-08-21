package com.studyink.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

internal object ReaderDevicePreviewFixtures {
    fun teacherPhone() = ReaderUiState(
        bookId = "preview-book",
        bookTitle = "학생 풀이 검토 — 문장 구조와 어휘 연습",
        pageCount = 18,
        documentReady = true,
        pageNumber = 1,
        attemptNo = 2,
        role = ReaderRole.TEACHER_PHONE,
        capabilities = ReaderCapabilities.forRole(ReaderRole.TEACHER_PHONE),
        canUndo = true,
        canRedo = true,
        studentPageNumber = 2,
    )

    fun studentTablet() = ReaderUiState(
        bookId = "preview-book",
        bookTitle = "영어 문제집 — 문장 구조와 어휘 연습",
        pageCount = 18,
        documentReady = true,
        pageNumber = 1,
        attemptNo = 1,
        role = ReaderRole.STUDENT,
        capabilities = ReaderCapabilities.forRole(ReaderRole.STUDENT),
        canUndo = true,
        canRedo = false,
    )
}

@Composable
internal fun ReaderDevicePreviewFrame(
    state: ReaderUiState,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFE1E2E7)),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 76.dp),
                contentAlignment = Alignment.Center,
            ) {
                val isTablet = maxWidth >= 700.dp
                val pagePadding = if (isTablet) 80.dp else 14.dp
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = pagePadding, vertical = 18.dp)
                        .widthIn(max = 640.dp),
                    color = Color(0xFFFFFEFC),
                    shadowElevation = 3.dp,
                ) {
                    Column(modifier = Modifier.padding(28.dp)) {
                        Text(
                            text = "Unit 2  Reading & Vocabulary",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF27324A),
                        )
                        Text(
                            text = "Read the passage and answer the questions.",
                            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF667085),
                        )
                        MockWorkbookPage(modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isTablet) 820.dp else 440.dp))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(76.dp),
            ) {
                ReaderTopChrome(
                    state = state,
                    expanded = true,
                    onToggleExpanded = {},
                    onPrevious = {},
                    onNext = {},
                    onExitToLibrary = {},
                    onSubmit = {},
                    onPreviousAttempt = {},
                    onNextAttempt = {},
                    onPublish = {},
                    onDismissDataError = {},
                )
            }

            overlay()
        }
    }
}

@Composable
private fun MockWorkbookPage(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val ink = Color(0xFF687086)
        val guide = Color(0xFFD9DDE7)
        val teacherRed = Color(0xFFD94747)
        val studentBlue = Color(0xFF3568E8)
        val left = size.width * 0.04f
        val right = size.width * 0.96f
        repeat(12) { index ->
            val y = size.height * (0.08f + index * 0.066f)
            drawLine(
                color = if (index % 4 == 0) ink else guide,
                start = Offset(left, y),
                end = Offset(if (index % 4 == 0) size.width * 0.78f else right, y),
                strokeWidth = if (index % 4 == 0) 3f else 2f,
                cap = StrokeCap.Round,
            )
        }
        drawLine(
            color = studentBlue,
            start = Offset(size.width * 0.18f, size.height * 0.32f),
            end = Offset(size.width * 0.62f, size.height * 0.35f),
            strokeWidth = 5f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = teacherRed,
            start = Offset(size.width * 0.72f, size.height * 0.49f),
            end = Offset(size.width * 0.78f, size.height * 0.56f),
            strokeWidth = 6f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = teacherRed,
            start = Offset(size.width * 0.78f, size.height * 0.56f),
            end = Offset(size.width * 0.9f, size.height * 0.42f),
            strokeWidth = 6f,
            cap = StrokeCap.Round,
        )
    }
}

@Preview(
    name = "Reader 전체 화면",
    group = "교사폰 S23 Ultra · 세로",
    widthDp = 412,
    heightDp = 892,
    showBackground = true,
)
@Composable
private fun TeacherPhoneReaderDevicePreview() {
    ReaderDevicePreviewFrame(ReaderDevicePreviewFixtures.teacherPhone())
}

@Preview(
    name = "Reader 전체 화면",
    group = "학생 Tab S11 · 세로",
    widthDp = 800,
    heightDp = 1280,
    showBackground = true,
)
@Composable
private fun StudentTabletReaderDevicePreview() {
    ReaderDevicePreviewFrame(ReaderDevicePreviewFixtures.studentTablet())
}
