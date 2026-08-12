package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantWorkspaceTest {
    private lateinit var database: AnnotationDatabase
    private lateinit var workspace: AssistantWorkspace

    @Before fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AnnotationDatabase::class.java).build()
        workspace = AssistantWorkspace(database) { 10L }
        database.learningDao().insertBookRevision(BookRevisionEntity("revision", "book", "doc", 1, "hash", "Book", 1))
        workspace.ensureDefaultTemplates()
    }

    @After fun cleanup() = database.close()

    @Test fun unfinishedJobSurvivesRepositoryRecreation() = runTest {
        val id = workspace.prepareJob(PageSelection("revision", "page", CanonicalRect(.1f, .2f, .8f, .9f)), AssistantRequestType.EASY_EXPLANATION, "easy-child")
        val restored = AssistantWorkspace(database) { 20L }.unfinishedJobs().single()
        assertEquals(id, restored.id)
        assertEquals(AssistantJobStatus.DRAFT, restored.status)
        assertFalse(restored.selection.includeStudentInk)
    }
}
