package com.studyink.teacher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.studyink.annotation.storage.AssistantJob
import com.studyink.annotation.storage.AssistantWorkspace
import kotlinx.coroutines.launch

class AssistantJobActivity : ComponentActivity() {
    private lateinit var workspace: AssistantWorkspace
    private var job by mutableStateOf<AssistantJob?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TeacherSession.controller.isValid()) { finish(); return }
        workspace = AssistantWorkspace.open(this)
        val id = intent.getStringExtra("assistantJobId") ?: run { finish(); return }
        lifecycleScope.launch { job = workspace.getJob(id) }
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("GPT 요청 자료", style = MaterialTheme.typography.headlineSmall)
                    Text(job?.promptText ?: "요청 자료 준비 중…", Modifier.padding(vertical = 20.dp))
                    Text("선택 영역 이미지는 원본 PDF 좌표에서 생성됐으며 학생 필기는 기본 제외됩니다.")
                    Button(onClick = ::finish, modifier = Modifier.padding(top = 20.dp)) { Text("닫기") }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::workspace.isInitialized) workspace.close()
        super.onDestroy()
    }
}
