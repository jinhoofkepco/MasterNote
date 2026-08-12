package com.studyink.teacher

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.studyink.reader.TeacherAccessSessionController
import com.studyink.reader.TeacherRouteAccess

sealed interface TeacherAuthenticationResult {
    data object Success : TeacherAuthenticationResult
    data object Cancelled : TeacherAuthenticationResult
    data class Unavailable(val reason: String) : TeacherAuthenticationResult
    data class Error(val reason: String) : TeacherAuthenticationResult
}

interface TeacherAccessAuthenticator {
    fun authenticate(onResult: (TeacherAuthenticationResult) -> Unit)
    fun isSessionValid(): Boolean
    fun invalidate()
}

class TeacherGateCoordinator(
    private val authenticator: TeacherAccessAuthenticator,
    private val openTeacherHome: () -> Unit,
    private val showMessage: (String) -> Unit,
) {
    fun requestAuthentication() = authenticator.authenticate { result ->
        when (result) {
            TeacherAuthenticationResult.Success -> openTeacherHome()
            TeacherAuthenticationResult.Cancelled -> showMessage("인증이 취소되었습니다")
            is TeacherAuthenticationResult.Unavailable -> showMessage(result.reason)
            is TeacherAuthenticationResult.Error -> showMessage(result.reason)
        }
    }
}

typealias TeacherSessionController = TeacherAccessSessionController

object TeacherSession { val controller: TeacherSessionController = TeacherRouteAccess.session }

class AndroidTeacherAccessAuthenticator(
    private val activity: FragmentActivity,
    private val session: TeacherSessionController = TeacherSession.controller,
) : TeacherAccessAuthenticator {
    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    override fun authenticate(onResult: (TeacherAuthenticationResult) -> Unit) {
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(TeacherAuthenticationResult.Unavailable("기기 잠금 또는 생체 인증을 먼저 설정해 주세요"))
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    session.authenticated()
                    onResult(TeacherAuthenticationResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) onResult(TeacherAuthenticationResult.Cancelled)
                    else onResult(TeacherAuthenticationResult.Error(errString.toString()))
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("선생 모드 인증")
                .setSubtitle("생체 인증 또는 기기 잠금으로 확인합니다")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    override fun isSessionValid(): Boolean = session.isValid()
    override fun invalidate() = session.invalidate()
}
