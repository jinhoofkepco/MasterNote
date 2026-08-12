package com.studyink.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.studyink.reader.TeacherRouteAccess
import com.studyink.teacher.TeacherHomeActivity
import com.studyink.teacher.TeacherModeGateActivity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeacherModeAccessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @After fun resetSession() = TeacherRouteAccess.session.invalidate()

    @Test fun directTeacherHomeWithoutSessionIsRedirectedToAuthenticationGate() {
        TeacherRouteAccess.session.invalidate()
        ActivityScenario.launch<TeacherHomeActivity>(
            Intent(context, TeacherHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).use {
            assertNotNull(device.wait(Until.findObject(By.text("기기 인증")), 5_000))
        }
    }

    @Test fun validProcessSessionOpensTeacherHome() {
        TeacherRouteAccess.session.authenticated()
        ActivityScenario.launch<TeacherHomeActivity>(
            Intent(context, TeacherHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).use {
            assertNotNull(device.wait(Until.findObject(By.text("교재 준비")), 5_000))
        }
    }
}
