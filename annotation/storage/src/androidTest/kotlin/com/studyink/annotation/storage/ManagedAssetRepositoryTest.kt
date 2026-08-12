package com.studyink.annotation.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ManagedAssetRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AnnotationDatabase
    private lateinit var repository: ManagedAssetRepository

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AnnotationDatabase::class.java).build()
        repository = ManagedAssetRepository(context, database)
    }

    @After fun cleanup() {
        database.close()
        File(context.filesDir, "managed-assets").deleteRecursively()
    }

    @Test fun streamingImportDeduplicatesByShaAndVerifiesImage() = runTest {
        val bytes = png()
        val first = repository.importStream(ByteArrayInputStream(bytes), "first.png", "image/png")
        val second = repository.importStream(ByteArrayInputStream(bytes), "duplicate.png", "image/png")

        assertEquals(first.assetId, second.assetId)
        assertEquals(4, first.widthPx)
        assertTrue(repository.verify(first.assetId) is AssetVerificationResult.Valid)
        assertEquals(1, database.managedAssetDao().all().size)
    }

    @Test fun unsafeZipPathIsRejectedWithoutRegisteringAsset() = runTest {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { output ->
                output.putNextEntry(ZipEntry("../escape.png"))
                output.write(png())
                output.closeEntry()
            }
        }.toByteArray()

        val failed = runCatching { repository.importStream(ByteArrayInputStream(zip), "bad.zip") }.isFailure
        assertTrue(failed)
        assertTrue(database.managedAssetDao().all().isEmpty())
    }

    @Test fun committedFileWithoutDbRowIsCollectedAfterFault() = runTest {
        var committed: File? = null
        repository = ManagedAssetRepository(context, database, nowEpochMillis = { 100_000L }) { file ->
            committed = file
            throw IllegalStateException("injected")
        }
        assertTrue(runCatching { repository.importStream(ByteArrayInputStream(png()), "fault.png") }.isFailure)
        assertTrue(committed?.isFile == true)
        committed?.setLastModified(1L)
        assertEquals(1, repository.collectGarbage(gracePeriodMillis = 1))
        assertTrue(database.managedAssetDao().all().isEmpty())
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
