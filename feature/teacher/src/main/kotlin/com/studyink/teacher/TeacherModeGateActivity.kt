package com.studyink.teacher

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

class TeacherModeGateActivity : FragmentActivity() {
    private lateinit var authenticator: TeacherAccessAuthenticator
    private lateinit var coordinator: TeacherGateCoordinator
    private var message by mutableStateOf("학생 기록을 보호하기 위해 인증이 필요합니다")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authenticator = AndroidTeacherAccessAuthenticator(this)
        coordinator = TeacherGateCoordinator(authenticator, ::openTeacherHome) { message = it }
        if (authenticator.isSessionValid()) {
            openTeacherHome()
            return
        }
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("선생 모드", style = MaterialTheme.typography.headlineMedium)
                    Text(message, modifier = Modifier.padding(vertical = 20.dp))
                    Button(onClick = coordinator::requestAuthentication) { Text("기기 인증") }
                }
            }
        }
    }

    private fun openTeacherHome() {
        startActivity(Intent(this, TeacherHomeActivity::class.java))
        finish()
    }
}
