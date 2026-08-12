package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AnnotationDatabaseSchemaTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AnnotationDatabase::class.java,
    )

    @After
    fun deleteDatabase() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun versionOneMigratesToLatestAndPreservesAnnotationRows() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO annotation_documents(documentId, currentRevision, createdAtEpochMillis)
                VALUES('preserved-document', 7, 1234)
                """.trimIndent()
            )
            close()
        }

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnnotationDatabase::class.java,
            TEST_DATABASE,
        ).build()
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            cursor.moveToFirst()
            assertEquals(7, cursor.getInt(0))
        }
        val preserved = runBlocking { database.annotationDao().document("preserved-document") }
        assertEquals(7L, preserved?.currentRevision)
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='attempts'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    fun versionTwoMigratesToLatestWithTeacherAndRemoteTables() {
        migrationHelper.createDatabase(TEST_DATABASE, 2).apply { close() }

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnnotationDatabase::class.java,
            TEST_DATABASE,
        ).build()
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            cursor.moveToFirst()
            assertEquals(7, cursor.getInt(0))
        }
        listOf(
            "teacher_profiles", "teacher_prep_pages", "submission_reviews", "review_pages",
            "review_stroke_refs", "remote_outbox", "remote_inbox_sequences", "remote_applied_operations",
            "remote_replica_pages", "remote_replica_strokes",
            "managed_assets",
            "answer_documents", "answer_page_links", "answer_bookmarks",
        )
            .forEach { table ->
                database.openHelper.writableDatabase.query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'"
                ).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("Missing $table", 1, cursor.getInt(0))
                }
            }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "annotation-schema-test.db"
    }
}
