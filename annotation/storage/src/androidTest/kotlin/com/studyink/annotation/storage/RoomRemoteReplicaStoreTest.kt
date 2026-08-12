package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyink.remote.protocol.RemoteDurableOperation
import com.studyink.remote.protocol.RemoteOperationType
import com.studyink.remote.protocol.RemoteStrokeAsset
import com.studyink.remote.protocol.RemoteStrokePoint
import com.studyink.remote.storage.RemoteReplicaPage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRemoteReplicaStoreTest {
    private lateinit var database: AnnotationDatabase
    private lateinit var store: RoomRemoteReplicaStore

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AnnotationDatabase::class.java,
        ).build()
        store = RoomRemoteReplicaStore(database)
    }

    @After fun closeDatabase() = database.close()

    @Test fun checkpointReplacementRemovesOldStrokesInOneTransaction() = runTest {
        store.replacePageAtomically(page(1, listOf(stroke("old"))))
        store.replacePageAtomically(page(2, listOf(stroke("new-1"), stroke("new-2"))))

        val restored = store.page("session", "page")
        assertEquals(2L, restored?.layerRevision)
        assertEquals(listOf("new-1", "new-2"), restored?.strokes?.map { it.strokeId })
    }

    @Test fun duplicateOperationAdvancesSequenceWithoutApplyingStrokeTwice() = runTest {
        val operation = RemoteDurableOperation(
            "operation", RemoteOperationType.ADD_STROKE, "page", 1, addedStrokes = listOf(stroke("one")),
        )
        assertTrue(store.applyOperationAtomically("session", 1, "message-1", operation, 1))
        assertTrue(store.applyOperationAtomically("session", 2, "message-2", operation, 2))

        assertEquals(listOf("one"), store.page("session", "page")?.strokes?.map { it.strokeId })
        assertEquals(2L, database.remoteDao().maxInboxSequence("session"))
    }

    private fun page(revision: Long, strokes: List<RemoteStrokeAsset>) = RemoteReplicaPage(
        "session", "page", 0, revision, 0, revision, strokes,
    )
    private fun stroke(id: String) = RemoteStrokeAsset(
        id, 0, 0, -1, 4f, listOf(RemoteStrokePoint(1f, 2f, .5f, 1)),
    )
}
