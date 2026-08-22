package com.studyink.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyink.annotation.storage.PageOperationLogStore
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageOperationLogStoreProviderTest {
    @Test
    fun applicationProviderReturnsTheSameStoreInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val first = PageOperationLogStore.get(context)
        val second = PageOperationLogStore.get(context.applicationContext)

        assertSame(first, second)
    }
}
