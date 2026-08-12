package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningDaoTest {
    private lateinit var database: AnnotationDatabase
    private lateinit var dao: LearningDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AnnotationDatabase::class.java).build()
        dao = database.learningDao()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun fixedContentFixtureStoresProfileBookActivitiesAndOrderedPages() = runTest {
        insertFixture()

        val revision = requireNotNull(dao.bookRevision(REVISION_ID))
        val activity = requireNotNull(dao.activity(ACTIVITY_ONE))
        val pages = dao.activityPages(ACTIVITY_ONE)

        assertEquals("테스트 학습지", revision.title)
        assertEquals("Unit 1", activity.title)
        assertEquals(listOf(0, 1), pages.map { it.pageNumber })
        assertEquals(listOf(0, 1), pages.map { it.pageOrder })
    }

    @Test
    fun progressProjectionStartsEmptyWithoutReadingStrokePayloads() = runTest {
        insertFixture()

        val rows = dao.observeActivityProgress(PROFILE_ID, REVISION_ID).first()

        assertEquals(listOf("Unit 1", "Unit 2"), rows.map { it.title })
        assertEquals(listOf(0, 0), rows.map { it.attemptCount })
        assertEquals(listOf(0, 0), rows.map { it.submissionCount })
        assertFalse(rows.any { it.hasDraft })
    }

    private suspend fun insertFixture() {
        dao.insertProfile(LearnerProfileEntity(PROFILE_ID, "학생", 1L))
        dao.insertBookRevision(
            BookRevisionEntity(
                revisionId = REVISION_ID,
                bookId = "test-book",
                documentId = DOCUMENT_ID,
                revisionNumber = 1,
                contentHash = "fixture-hash",
                title = "테스트 학습지",
                createdAtEpochMillis = 1L,
            )
        )
        dao.insertActivity(LearningActivityEntity(ACTIVITY_ONE, REVISION_ID, "Unit 1", 0, "INK_ONLY"))
        dao.insertActivity(LearningActivityEntity(ACTIVITY_TWO, REVISION_ID, "Unit 2", 1, "INK_ONLY"))
        dao.insertActivityPages(
            listOf(
                ActivityPageRefEntity(ACTIVITY_ONE, "$DOCUMENT_ID:page:0", 0, 0),
                ActivityPageRefEntity(ACTIVITY_ONE, "$DOCUMENT_ID:page:1", 1, 1),
                ActivityPageRefEntity(ACTIVITY_TWO, "$DOCUMENT_ID:page:2", 2, 0),
            )
        )
    }

    private companion object {
        const val PROFILE_ID = "default-student"
        const val REVISION_ID = "test-book-r1"
        const val DOCUMENT_ID = "test-document"
        const val ACTIVITY_ONE = "activity-1"
        const val ACTIVITY_TWO = "activity-2"
    }
}
