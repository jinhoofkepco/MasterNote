package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class LibraryRepositoryTest {
    private lateinit var db: AnnotationDatabase; private lateinit var repo: LibraryRepository
    @Before fun setup() = runTest { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AnnotationDatabase::class.java).build(); repo = LibraryRepository(db) { 10 }; repo.ensureRoot() }
    @After fun close() = db.close()
    @Test fun duplicateNormalizedSiblingNameRejected() = runTest { repo.createFolder(LibraryRepository.ROOT_ID, "English"); Assert.assertTrue(runCatching { repo.createFolder(LibraryRepository.ROOT_ID, " english  ") }.isFailure) }
    @Test fun movingFolderIntoDescendantRejected() = runTest { val a=repo.createFolder(LibraryRepository.ROOT_ID,"A"); val b=repo.createFolder(a.id,"B"); val c=repo.createFolder(b.id,"C"); Assert.assertTrue(runCatching { repo.moveFolder(a.id,c.id) }.isFailure) }
    @Test fun movingBookChangesPlacementOnlyAndKeepsRevision() = runTest { val a=repo.createFolder(LibraryRepository.ROOT_ID,"A"); val b=repo.createFolder(LibraryRepository.ROOT_ID,"B"); db.learningDao().insertBookRevision(BookRevisionEntity("r","book","doc",1,"hash","Book",1)); repo.registerBook("book","Book","r",a.id); repo.moveBook("book",b.id); Assert.assertEquals("r", repo.observeBooks(b.id).first().single().currentRevisionId); Assert.assertNotNull(db.learningDao().bookRevision("r")) }
    @Test fun trashAndRestoreFolder() = runTest { val a=repo.createFolder(LibraryRepository.ROOT_ID,"A"); repo.trashFolder(a.id,false); Assert.assertTrue(repo.observeFolders().first().isEmpty()); repo.restoreFolder(a.id); Assert.assertEquals(a.id,repo.observeFolders().first().single().id) }
}
