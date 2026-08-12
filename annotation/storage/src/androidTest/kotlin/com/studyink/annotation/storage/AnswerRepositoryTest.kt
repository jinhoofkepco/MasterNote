package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnswerRepositoryTest {
    private lateinit var database: AnnotationDatabase
    private lateinit var repository: AnswerRepository

    @Before fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AnnotationDatabase::class.java).build()
        repository = AnswerRepository(database) { 100L }
        database.learningDao().insertBookRevision(BookRevisionEntity(REVISION, "book", "document", 1, "hash", "Book", 1L))
        database.learningDao().insertActivity(LearningActivityEntity(ACTIVITY, REVISION, "Activity", 0, "INK_ONLY"))
        database.learningDao().insertActivityPages(listOf(ActivityPageRefEntity(ACTIVITY, PAGE_1, 12, 0), ActivityPageRefEntity(ACTIVITY, PAGE_2, 13, 1)))
        database.teacherDao().insertTeacher(TeacherProfileEntity(TEACHER, "Teacher", 1L))
        database.managedAssetDao().insert(ManagedAssetEntity(ASSET, "sha", "application/pdf", "answers.pdf", 10, "aa/sha.pdf", null, null, 50, 1L, 1L))
    }

    @After fun cleanup() = database.close()

    @Test fun resolutionUsesRegionThenPageThenActivityAndBookmark() = runTest {
        val document = repository.linkAnswerDocument(REVISION, ManagedAssetId(ASSET), AnswerKind.ANSWER, "Answers")
        repository.savePageLink(REVISION, document, ACTIVITY, null, null, 30)
        repository.savePageLink(REVISION, document, ACTIVITY, PAGE_1, null, 31)
        repository.savePageLink(REVISION, document, ACTIVITY, PAGE_1, CanonicalRect(0f, 0f, .5f, .5f), 32)
        repository.saveBookmark(TEACHER, document, AnswerBookmark(40, .5f, .5f, 2f))

        assertLocation(document, 32, AnswerLocationSource.REGION, PAGE_1, CanonicalRect(.1f, .1f, .2f, .2f))
        assertLocation(document, 31, AnswerLocationSource.PAGE, PAGE_1, CanonicalRect(.6f, .6f, .8f, .8f))
        assertLocation(document, 30, AnswerLocationSource.ACTIVITY, PAGE_2, null)
        assertEquals(AnswerLocationSource.BOOKMARK, repository.resolveAnswerLocation(TEACHER, REVISION, null, null, null).source)
    }

    @Test fun offsetPreviewRejectsOutOfRangeAndSavesAtomically() = runTest {
        val document = repository.linkAnswerDocument(REVISION, ManagedAssetId(ASSET), AnswerKind.ANSWER, "Answers")
        assertTrue(repository.previewOffsetLinks(ACTIVITY, document, 48).all { it.valid })
        assertEquals(2, repository.saveOffsetLinks(ACTIVITY, document, 48).size)
        val bad = repository.previewOffsetLinks(ACTIVITY, document, 49)
        assertTrue(bad.first().valid)
        assertFalse(bad.last().valid)
        assertTrue(runCatching { repository.saveOffsetLinks(ACTIVITY, document, 49) }.isFailure)
    }

    @Test fun linkFromAnotherRevisionIsRejected() = runTest {
        database.learningDao().insertBookRevision(BookRevisionEntity("other", "book", "document2", 2, "hash2", "Other", 1L))
        val document = repository.linkAnswerDocument(REVISION, ManagedAssetId(ASSET), AnswerKind.ANSWER, "Answers")
        assertTrue(runCatching { repository.savePageLink("other", document, null, PAGE_1, null, 1) }.isFailure)
    }

    private suspend fun assertLocation(document: String, page: Int, source: AnswerLocationSource, problemPage: String, region: CanonicalRect?) {
        val location = repository.resolveAnswerLocation(TEACHER, REVISION, ACTIVITY, problemPage, region, document)
        assertEquals(page, location.pageIndex)
        assertEquals(source, location.source)
    }

    private companion object {
        const val REVISION = "revision"
        const val ACTIVITY = "activity"
        const val PAGE_1 = "page-1"
        const val PAGE_2 = "page-2"
        const val TEACHER = "teacher"
        const val ASSET = "asset"
    }
}
