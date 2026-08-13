package com.studyink.teacher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.studyink.annotation.storage.AnswerDocument
import com.studyink.annotation.storage.AnswerKind
import com.studyink.annotation.storage.AnswerRepository
import com.studyink.annotation.storage.ManagedAssetRepository
import com.studyink.reader.SampleLearningContent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AnswerSetupActivity : ComponentActivity() {
    private lateinit var answers: AnswerRepository
    private lateinit var assets: ManagedAssetRepository
    private var documents by mutableStateOf<List<AnswerDocument>>(emptyList())
    private var problemPage by mutableStateOf("1")
    private var answerPage by mutableStateOf("1")
    private var status by mutableStateOf("정답 PDF를 가져오세요")

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val asset = assets.importUri(uri)
                answers.linkAnswerDocument(SampleLearningContent.REVISION_ID, asset.assetId, AnswerKind.ANSWER, asset.originalFileName)
            }.onSuccess { status = "정답 파일을 연결했습니다" }
                .onFailure { status = "정답 파일 오류: ${it.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TeacherSession.controller.isValid()) { finish(); return }
        answers = AnswerRepository.open(this); assets = ManagedAssetRepository.open(this)
        lifecycleScope.launch { answers.observeDocuments(SampleLearningContent.REVISION_ID).collectLatest { documents = it } }
        setContent { MaterialTheme { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("정답지 설정", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { openDocument.launch(arrayOf("application/pdf", "application/zip")) }) { Text("정답 PDF/이미지 ZIP 가져오기") }
            Text("연결된 정답지: ${documents.joinToString { it.displayName }.ifBlank { "없음" }}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(problemPage, { problemPage = it.filter(Char::isDigit) }, label = { Text("문제 페이지") }, modifier = Modifier.weight(1f))
                OutlinedTextField(answerPage, { answerPage = it.filter(Char::isDigit) }, label = { Text("정답 페이지") }, modifier = Modifier.weight(1f))
            }
            Button(onClick = ::linkCurrentPages, enabled = documents.isNotEmpty()) { Text("현재 두 페이지 연결") }
            Button(onClick = ::applyOffset, enabled = documents.isNotEmpty()) { Text("전체 페이지에 같은 차이 미리검증 후 적용") }
            Text(status)
            Button(onClick = ::finish) { Text("닫기") }
        } } }
    }

    private fun linkCurrentPages() {
        val problem = problemPage.toIntOrNull()?.minus(1) ?: return
        val answer = answerPage.toIntOrNull()?.minus(1) ?: return
        val seed = SampleLearningContent.createSeed(this)
        val target = seed.activities.flatMap { activity -> activity.pages.map { activity.activity.activityId.value to it } }.firstOrNull { it.second.pageNumber == problem }
        val document = documents.firstOrNull() ?: return
        if (target == null) { status = "문제 페이지가 현재 책 범위를 벗어났습니다"; return }
        lifecycleScope.launch {
            runCatching { answers.savePageLink(SampleLearningContent.REVISION_ID, document.id, target.first, target.second.pageId.value, null, answer) }
                .onSuccess { status = "문제 ${problem + 1} → 정답 ${answer + 1} 연결 완료" }
                .onFailure { status = "연결 실패: ${it.message}" }
        }
    }

    private fun applyOffset() {
        val firstAnswer = answerPage.toIntOrNull()?.minus(1) ?: return
        val reviewActivity = SampleLearningContent.createSeed(this).activities.first { it.pages.size > 1 }.activity.activityId.value
        val document = documents.firstOrNull() ?: return
        lifecycleScope.launch {
            runCatching {
                val preview = answers.previewOffsetLinks(reviewActivity, document.id, firstAnswer)
                check(preview.all { it.valid })
                answers.saveOffsetLinks(reviewActivity, document.id, firstAnswer).size
            }.onSuccess { status = "${it}개 페이지 offset 연결 완료" }
                .onFailure { status = "범위를 벗어나 저장하지 않았습니다: ${it.message}" }
        }
    }

    override fun onDestroy() {
        if (::answers.isInitialized) answers.close(); if (::assets.isInitialized) assets.close(); super.onDestroy()
    }
}
