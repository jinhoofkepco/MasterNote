package com.studyink.annotation.storage

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class BookImportRepositoryTest {
    private lateinit var context:Context;private lateinit var db:AnnotationDatabase;private lateinit var repo:BookImportRepository
    @Before fun setup()=runTest{context=ApplicationProvider.getApplicationContext();db=Room.inMemoryDatabaseBuilder(context,AnnotationDatabase::class.java).build();LibraryRepository(db).ensureRoot();repo=BookImportRepository(context,db){100}}
    @After fun close()=db.close()
    @Test fun pdfWaitsForConfirmationThenCommitsWholeBook()=runTest{val id=repo.create(Uri.fromFile(pdf()),LibraryRepository.ROOT_ID);repo.process(id);Assert.assertEquals(ImportState.WAITING_USER_CONFIRMATION,repo.observe(id).first()!!.state);repo.confirm(id);repo.process(id);val done=repo.observe(id).first()!!;Assert.assertEquals(ImportState.SUCCEEDED,done.state);Assert.assertNotNull(db.learningDao().bookRevision(done.revisionId!!));Assert.assertEquals(1,LibraryRepository(db).observeBooks().first().size)}
    @Test fun interruptedSessionCanBeResetForUniqueWorkerRetry()=runTest{val id=repo.create(Uri.fromFile(pdf()),LibraryRepository.ROOT_ID);val row=db.importDao().session(id)!!;db.importDao().update(row.copy(state=ImportState.COPYING.name));Assert.assertEquals(listOf(id),repo.resumeInterrupted());Assert.assertEquals(ImportState.CREATED,repo.observe(id).first()!!.state)}
    private fun pdf():File{val f=File(context.cacheDir,"import.pdf");val d=PdfDocument();repeat(2){i->val p=d.startPage(PdfDocument.PageInfo.Builder(100,100,i+1).create());d.finishPage(p)};FileOutputStream(f).use(d::writeTo);d.close();return f}
}
