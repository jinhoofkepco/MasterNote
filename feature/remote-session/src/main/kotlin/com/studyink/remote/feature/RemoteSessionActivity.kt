package com.studyink.remote.feature

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.studyink.remote.session.RemoteSessionRole
import kotlinx.coroutines.launch
import java.util.UUID

class RemoteSessionActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var reject: Button
    private lateinit var sessionCode: EditText
    private var started = false
    private val teacherSessionCode by lazy {
        intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank)
            ?: (100_000..999_999).random().toString()
    }
    private val role by lazy { RemoteSessionRole.valueOf(intent.getStringExtra(EXTRA_ROLE) ?: RemoteSessionRole.STUDENT.name) }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) startServiceFromUserAction() else status.text = "근거리 연결 권한이 필요합니다."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 22f; setPadding(24, 48, 24, 24) }
        primary = Button(this)
        reject = Button(this).apply { text = "거절"; visibility = android.view.View.GONE }
        sessionCode = EditText(this).apply {
            hint = "선생님 화면의 6자리 연결 번호"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            visibility = if (role == RemoteSessionRole.STUDENT) android.view.View.VISIBLE else android.view.View.GONE
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(status); addView(sessionCode); addView(primary); addView(reject)
        })
        if (role == RemoteSessionRole.TEACHER) {
            status.text = "원격 수업 연결 번호\n$teacherSessionCode"
            ensurePermissionsAndStart(teacherSessionCode)
        } else {
            status.text = "선생님 화면의 연결 번호를 입력하세요."
            primary.text = "선생님 기기 찾기"
            primary.setOnClickListener {
                val code = sessionCode.text.toString().trim()
                if (code.length != 6) status.text = "6자리 연결 번호를 입력하세요."
                else ensurePermissionsAndStart(code)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoteSessionRuntime.snapshot.collect { snapshot ->
                    val pairing = snapshot.pairing
                    when {
                        pairing != null -> {
                            status.text = "연결 번호 ${pairing.authenticationDigits}\n양쪽 번호가 같은지 확인하세요."
                            primary.text = "번호 일치 · 연결"
                            primary.setOnClickListener { RemoteSessionRuntime.acceptPairing() }
                            reject.visibility = android.view.View.VISIBLE
                            reject.setOnClickListener { RemoteSessionRuntime.rejectPairing() }
                        }
                        !started -> Unit
                        role == RemoteSessionRole.STUDENT && snapshot.availableEndpoints.isNotEmpty() -> {
                            val endpoint = snapshot.availableEndpoints.first()
                            status.text = "${endpoint.displayName} 발견"
                            primary.text = "선생님 기기에 연결"
                            primary.setOnClickListener { RemoteSessionRuntime.connect(endpoint.endpointId) }
                            reject.visibility = android.view.View.GONE
                        }
                        else -> {
                            status.text = if (role == RemoteSessionRole.TEACHER) {
                                "연결 번호 $teacherSessionCode · ${snapshot.state}"
                            } else "원격 수업 · ${snapshot.state}"
                            primary.text = "세션 종료"
                            primary.setOnClickListener { RemoteSessionService.stop(this@RemoteSessionActivity); finish() }
                            reject.visibility = android.view.View.GONE
                        }
                    }
                }
            }
        }
    }

    private var pendingSessionCode: String? = null

    private fun ensurePermissionsAndStart(code: String) {
        pendingSessionCode = code
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startServiceFromUserAction(code) else permissionRequest.launch(missing.toTypedArray())
    }

    private fun startServiceFromUserAction() {
        pendingSessionCode?.let(::startServiceFromUserAction)
    }

    private fun startServiceFromUserAction(code: String) {
        if (started) return
        started = true
        sessionCode.isEnabled = false
        val preferences = getSharedPreferences("remote-device", MODE_PRIVATE)
        val deviceId = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("deviceId", it).apply()
        }
        RemoteSessionService.start(
            this, code,
            deviceId, if (role == RemoteSessionRole.TEACHER) "선생님" else "학생", role,
        )
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val EXTRA_ROLE = "role"
        private const val EXTRA_SESSION_ID = "sessionId"
        fun intent(context: Context, role: RemoteSessionRole, sessionId: String? = null) =
            Intent(context, RemoteSessionActivity::class.java).putExtra(EXTRA_ROLE, role.name).apply {
                sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            }
    }
}
