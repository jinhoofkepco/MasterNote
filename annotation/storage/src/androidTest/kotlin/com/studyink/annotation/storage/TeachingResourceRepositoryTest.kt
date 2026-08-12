package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeachingResourceRepositoryTest {
    private lateinit var database: AnnotationDatabase
    private lateinit var repository: TeachingResourceRepository

    @Before fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AnnotationDatabase::class.java).build()
        repository = TeachingResourceRepository(database) { 100L }
        database.learningDao().insertBookRevision(BookRevisionEntity("revision", "book", "doc", 1, "hash", "Book", 1))
        database.teacherDao().insertTeacher(TeacherProfileEntity("teacher", "Teacher", 1))
    }

    @After fun cleanup() = database.close()

    @Test fun revisionsAreImmutableAndCurrentPointerMoves() = runTest {
        val resource = repository.createDraft("revision", TeachingResourceType.TEXT, TeachingResourceCategory.GENERAL, "설명", TeachingResourceSource.ASSISTANT_EXTERNAL, "teacher")
        val first = repository.addRevision(resource, text = "원본")
        val second = repository.addRevision(resource, text = "수정본")
        assertNotEquals(first, second)
        assertEquals("원본", repository.getResourceRevision(first).text)
        assertEquals("수정본", repository.getResourceRevision(second).text)
        repository.linkToPage(resource, "page-1")
        repository.publish(resource)
        assertEquals(TeachingResourceStatus.PUBLISHED, repository.observePageResources("revision", "page-1").first().single().status)
    }

    @Test fun archivedResourceDisappearsButRevisionRemainsReadable() = runTest {
        val resource = repository.createDraft("revision", TeachingResourceType.TEXT, TeachingResourceCategory.CONCEPT, "개념", TeachingResourceSource.MANUAL, "teacher")
        val revision = repository.addRevision(resource, text = "내용")
        repository.linkToPage(resource, "page-1")
        repository.archive(resource)
        assertTrue(repository.observePageResources("revision", "page-1").first().isEmpty())
        assertEquals("내용", repository.getResourceRevision(revision).text)
    }
}
