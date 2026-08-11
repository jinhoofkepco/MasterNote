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
    fun exportedVersionOneSchemaCreatesAndOpensWithoutDestructiveFallback() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).close()

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnnotationDatabase::class.java,
            TEST_DATABASE,
        ).build()
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "annotation-schema-test.db"
    }
}
