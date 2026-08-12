package com.studyink.teacher

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.studyink.annotation.storage.ManagedAssetRepository
import com.studyink.annotation.storage.TeachingResourceContent
import com.studyink.annotation.storage.TeachingResourceRepository
import com.studyink.annotation.storage.TeachingResourceSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TeachingResourceActivity : ComponentActivity() {
    private lateinit var repository: TeachingResourceRepository
    private lateinit var assets: ManagedAssetRepository
    private var resources by mutableStateOf<List<TeachingResourceSummary>>(emptyList())
    private var selected by mutableStateOf<TeachingResourceContent?>(null)
    private var image by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TeacherSession.controller.isValid()) { finish(); return }
        repository = TeachingResourceRepository.open(this)
        assets = ManagedAssetRepository.open(this)
        val revision = intent.getStringExtra(EXTRA_REVISION) ?: run { finish(); return }
        val page = intent.getStringExtra(EXTRA_PAGE) ?: run { finish(); return }
        lifecycleScope.launch { repository.observePageResources(revision, page).collectLatest { resources = it } }
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Button(onClick = ::finish) { Text("닫기") }
                    Text("현재 페이지 설명 자료", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
                    selected?.let { content ->
                        content.text?.let { Text(it, Modifier.padding(vertical = 12.dp)) }
                        image?.let { Image(it, "설명 자료", Modifier.fillMaxWidth()) }
                        Button(onClick = { presentToStudent(content) }) { Text("학생에게 보여주기") }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    }
                    LazyColumn { items(resources, key = { it.resourceId }) { item ->
                        Text(item.title, Modifier.fillMaxWidth().clickable { open(item) }.padding(vertical = 14.dp))
                        HorizontalDivider()
                    } }
                }
            }
        }
    }

    private fun open(summary: TeachingResourceSummary) {
        val revisionId = summary.currentRevisionId ?: return
        lifecycleScope.launch {
            val content = repository.getResourceRevision(revisionId)
            selected = content
            image = content.imageAssetId?.let { id ->
                withContext(Dispatchers.IO) { BitmapFactory.decodeFile(assets.open(id).file.path)?.asImageBitmap() }
            }
        }
    }

    private fun presentToStudent(content: TeachingResourceContent) {
        lifecycleScope.launch {
            val file = content.imageAssetId?.let { assets.open(it).file }
            com.studyink.remote.feature.RemoteSessionRuntime.offerTeachingResource(
                title = resources.firstOrNull { it.currentRevisionId == content.revisionId }?.title ?: "설명 자료",
                text = content.text.orEmpty(),
                mimeType = if (file == null) "text/plain" else assets.open(requireNotNull(content.imageAssetId)).asset.mimeType,
                file = file,
            )
        }
    }

    override fun onDestroy() {
        if (::repository.isInitialized) repository.close()
        if (::assets.isInitialized) assets.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REVISION = "com.studyink.resource.REVISION"
        const val EXTRA_PAGE = "com.studyink.resource.PAGE"
    }
}
