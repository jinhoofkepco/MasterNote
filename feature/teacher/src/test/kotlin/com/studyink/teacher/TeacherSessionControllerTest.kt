package com.studyink.teacher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherSessionControllerTest {
    @Test fun processSessionExpiresAfterBackgroundTimeoutAndCanBeInvalidated() {
        var now = 0L
        val session = TeacherSessionController({ now }, 1_000L)
        assertFalse(session.isValid())
        session.authenticated()
        assertTrue(session.isValid())
        session.enteredBackground()
        now = 1_001L
        assertFalse(session.isValid())
        session.invalidate()
        assertFalse(session.isValid())
    }

    @Test fun returningBeforeTimeoutKeepsSession() {
        var now = 0L
        val session = TeacherSessionController({ now }, 1_000L)
        session.authenticated()
        session.enteredBackground()
        now = 999L
        session.enteredForeground()
        assertTrue(session.isValid())
    }

    @Test fun gateOpensOnlyOnSuccessAndKeepsStudentModeOnCancel() {
        var next: TeacherAuthenticationResult = TeacherAuthenticationResult.Cancelled
        var opened = false
        var message = ""
        val fake = object : TeacherAccessAuthenticator {
            override fun authenticate(onResult: (TeacherAuthenticationResult) -> Unit) = onResult(next)
            override fun isSessionValid() = false
            override fun invalidate() = Unit
        }
        val gate = TeacherGateCoordinator(fake, { opened = true }, { message = it })

        gate.requestAuthentication()
        assertFalse(opened)
        assertTrue(message.contains("취소"))
        next = TeacherAuthenticationResult.Success
        gate.requestAuthentication()
        assertTrue(opened)
    }
}
