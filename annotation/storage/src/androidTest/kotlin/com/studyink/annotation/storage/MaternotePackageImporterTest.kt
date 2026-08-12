package com.studyink.annotation.storage

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maternote.packageformat.codec.PackageJson
import com.maternote.packageformat.model.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class MaternotePackageImporterTest {
    private lateinit var context:Context;private lateinit var db:AnnotationDatabase;private lateinit var repo:BookImportRepository
    @Before fun setup()=runTest{context=ApplicationProvider.getApplicationContext();db=Room.inMemoryDatabaseBuilder(context,AnnotationDatabase::class.java).build();LibraryRepository(db).ensureRoot();repo=BookImportRepository(context,db){100}}
    @After fun close()=db.close()
    @Test fun packageImportsExactPagesAndActivitiesAndDuplicateIsNoOp()=runTest{val file=packageFile();val id=repo.create(Uri.fromFile(file),LibraryRepository.ROOT_ID);repo.process(id);repo.confirm(id);repo.process(id);val done=repo.observe(id).first()!!;Assert.assertEquals(ImportState.SUCCEEDED,done.state);Assert.assertEquals(3,db.learningDao().activityPages("a1").size+db.learningDao().activityPages("a2").size);val duplicate=repo.create(Uri.fromFile(file),LibraryRepository.ROOT_ID);repo.process(duplicate);Assert.assertEquals(ImportState.SUCCEEDED,repo.observe(duplicate).first()!!.state);Assert.assertEquals(1,LibraryRepository(db).observeBooks().first().size)}
    private fun packageFile():File{val pdf=File(context.cacheDir,"fixture.pdf");val d=PdfDocument();repeat(3){i->val p=d.startPage(PdfDocument.PageInfo.Builder(100,100,i+1).create());d.finishPage(p)};FileOutputStream(pdf).use(d::writeTo);d.close();val bytes=pdf.readBytes();val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)};val asset=AssetDefinition("main","assets/$hash.pdf","application/pdf",hash,bytes.size.toLong());val m=PackageManifest(packageId="pkg",createdAt="now",createdBy=CreatedBy("Studio","1"),requiredCapabilities=listOf("document.pdf","activity.basic"),book=BookDefinition("book","rev1",revisionNumber=1,title="Book"),assets=listOf(asset),document=DocumentDefinition("pdf","main"),pages=(1..3).map{PageDefinition("p$it",PageSource("pdfPage",it-1),100,100)},activities=listOf(ActivityDefinition("a1","A",0,pageIds=listOf("p1","p2")),ActivityDefinition("a2","B",1,pageIds=listOf("p3"))));val out=File(context.cacheDir,"fixture.mnote");ZipOutputStream(FileOutputStream(out)).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(PackageJson.encode(m).toByteArray());z.closeEntry();z.putNextEntry(ZipEntry(asset.path));z.write(bytes);z.closeEntry()};return out}
}
