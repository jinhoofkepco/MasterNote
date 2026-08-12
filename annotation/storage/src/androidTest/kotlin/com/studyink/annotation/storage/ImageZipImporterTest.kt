package com.studyink.annotation.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class ImageZipImporterTest {
    @Test fun naturalSortOrdersOneTwoTen() { assertEquals(listOf("1.jpg","2.jpg","10.jpg","11.jpg"),listOf("11.jpg","2.jpg","10.jpg","1.jpg").sortedWith(Comparator(ImageZipImporter::naturalCompare))) }
    @Test fun explicitOrderMustContainEveryImageExactlyOnce() { val file=zip(mapOf("1.jpg" to jpeg(),"2.jpg" to jpeg(),"page-order.json" to "[\"2.jpg\"]".toByteArray())); assertTrue(runCatching{ImageZipImporter.orderedPageNames(file)}.isFailure) }
    private fun zip(entries:Map<String,ByteArray>):File{val f=File(ApplicationProvider.getApplicationContext<Context>().cacheDir,"images-${System.nanoTime()}.zip");ZipOutputStream(FileOutputStream(f)).use{z->entries.forEach{(n,b)->z.putNextEntry(ZipEntry(n));z.write(b);z.closeEntry()}};return f}
    private fun jpeg()=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
}
