package com.studyink.teacher

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.studyink.annotation.storage.AssistantJob
import com.studyink.annotation.storage.AssistantWorkspace
import com.studyink.annotation.storage.ManagedAssetId
import com.studyink.annotation.storage.ManagedAssetRepository
import com.studyink.annotation.storage.ResourceTriggerType
import com.studyink.annotation.storage.TeachingResourceCategory
import com.studyink.annotation.storage.TeachingResourceRepository
import com.studyink.annotation.storage.TeachingResourceSource
import com.studyink.annotation.storage.TeachingResourceType
import kotlinx.coroutines.launch

class AssistantJobActivity : ComponentActivity() {
    private lateinit var workspace: AssistantWorkspace
    private lateinit var assets: ManagedAssetRepository
    private lateinit var resources: TeachingResourceRepository
    private var job by mutableStateOf<AssistantJob?>(null)
    private var title by mutableStateOf("쉬운 설명")
    private var resultText by mutableStateOf("")
    private var resultImage by mutableStateOf<ManagedAssetId?>(null)
    private var status by mutableStateOf("GPT 결과를 가져오세요")

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching { assets.importUri(uri) }.onSuccess {
                resultImage = it.assetId; markImported(); status = "이미지 결과를 가져왔습니다"
            }.onFailure { status = "이미지 가져오기 실패: ${it.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TeacherSession.controller.isValid()) { finish(); return }
        workspace = AssistantWorkspace.open(this); assets = ManagedAssetRepository.open(this); resources = TeachingResourceRepository.open(this)
        val id = intent.getStringExtra("assistantJobId")
        lifecycleScope.launch {
            job = if (id != null) workspace.getJob(id) else workspace.unfinishedJobs().firstOrNull()
            if (job == null) { finish(); return@launch }
            importSharedResult(intent)
        }
        setContent { MaterialTheme { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GPT 요청 자료", style = MaterialTheme.typography.headlineSmall)
            Text(job?.promptText ?: "요청 자료 준비 중…")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launchProvider(ExternalShareAssistantProvider()) }) { Text("GPT로 보내기") }
                Button(onClick = { launchProvider(CustomTabAssistantProvider()) }) { Text("브라우저로 열기") }
            }
            Text(status)
            OutlinedTextField(title, { title = it.take(120) }, label = { Text("자료 제목") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(resultText, { resultText = it }, label = { Text("GPT 결과 검토·수정") }, modifier = Modifier.fillMaxWidth().weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::pasteResult) { Text("명시적으로 붙여넣기") }
                Button(onClick = { pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("이미지 선택") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(false) }) { Text("초안 저장") }
                Button(onClick = { save(true) }) { Text("게시") }
                Button(onClick = ::finish) { Text("취소") }
            }
        } } }
    }

    private fun launchProvider(provider: AssistantProvider) {
        val current = job ?: return
        lifecycleScope.launch {
            val imageUri = current.imageAssetId?.let { id ->
                val file = assets.open(id).file
                FileProvider.getUriForFile(this@AssistantJobActivity, "$packageName.managed-assets", file)
            }
            provider.launch(this@AssistantJobActivity, current.promptText, imageUri)
            workspace.markExternalOpened(current.id)
        }
    }

    private fun pasteResult() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        resultText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (resultText.isNotBlank()) { status = "텍스트 결과를 가져왔습니다"; markImported() }
    }

    private fun markImported() { job?.let { current -> lifecycleScope.launch { workspace.markResultImported(current.id) } } }

    private suspend fun importSharedResult(source: Intent) {
        if (source.action != Intent.ACTION_SEND) return
        source.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { resultText = it }
        val image = androidx.core.content.IntentCompat.getParcelableExtra(source, Intent.EXTRA_STREAM, Uri::class.java)
        image?.let { resultImage = assets.importUri(it).assetId }
        if (resultText.isNotBlank() || resultImage != null) {
            workspace.markResultImported(requireNotNull(job).id)
            status = "공유된 GPT 결과를 가져왔습니다. 검토 후 저장하세요."
        }
    }

    private fun save(publish: Boolean) {
        val current = job ?: return
        if (resultText.isBlank() && resultImage == null) { status = "텍스트 또는 이미지 결과가 필요합니다"; return }
        lifecycleScope.launch {
            runCatching {
                val type = when { resultText.isNotBlank() && resultImage != null -> TeachingResourceType.TEXT_AND_IMAGE; resultImage != null -> TeachingResourceType.IMAGE; else -> TeachingResourceType.TEXT }
                val resource = resources.createDraft(current.selection.bookRevisionId, type, TeachingResourceCategory.GENERAL, title, TeachingResourceSource.ASSISTANT_EXTERNAL, "default-teacher")
                resources.addRevision(resource, resultText.takeIf(String::isNotBlank), imageAssetId = resultImage, sourcePrompt = current.promptText, providerName = "external")
                resources.linkToPage(resource, current.selection.pageId, current.selection.bounds, ResourceTriggerType.PAGE_RESOURCE_LIST)
                if (publish) resources.publish(resource)
                workspace.markSavedAsResource(current.id)
                resource
            }.onSuccess { status = if (publish) "페이지 자료로 게시했습니다" else "페이지 자료 초안을 저장했습니다" }
                .onFailure { status = "저장 실패: ${it.message}" }
        }
    }

    override fun onDestroy() {
        if (::workspace.isInitialized) workspace.close(); if (::assets.isInitialized) assets.close(); if (::resources.isInitialized) resources.close()
        super.onDestroy()
    }
}
