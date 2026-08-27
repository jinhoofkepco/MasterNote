package com.studyink.annotation.storage

import android.content.Context
import com.google.gson.stream.JsonReader
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.ANNOTATION_FORMAT_VERSION
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.OperationId
import com.studyink.core.model.PageBounds
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOperationLogStoreCheckpointStreamingTest {
    @Test
    fun readsExistingCheckpointShapeIncludingLegacyPointWithoutPressure() {
        val root = Files.createTempDirectory("masternote-checkpoint-legacy").toFile()
        try {
            val pageDirectory = root.resolve("$BOOK_ID/pages/$PAGE").apply { mkdirs() }
            val legacyAsset = JSONObject()
                .put("points", JSONArray()
                    .put(JSONArray().put(10.25).put(20.5))
                    .put(JSONArray().put(30.75).put(40.125).put(0.4)))
                .put("deviceId", "legacy-device")
                .put("logicalClock", 42L)
                .put("attemptNo", 3)
                .put("authorId", "student")
                .put("width", 2.5)
                .put("colorArgb", 0xFF123456.toInt())
                .put("tool", "PEN")
                .put("pageNumber", PAGE)
                .put("id", "legacy-stroke")
                .put("itemId", JSONObject.NULL)
                .put("publishedAt", JSONObject.NULL)
                .put("bounds", JSONArray().put(10.25).put(20.5).put(30.75).put(40.125))
                .put("createdAtEpochMillis", 1234L)
                .put("parentStrokeId", JSONObject.NULL)
                .put("formatVersion", ANNOTATION_FORMAT_VERSION)
                .put("ignoredFutureField", JSONObject().put("nested", true))
            val legacyRoot = JSONObject()
                .put("assets", JSONArray().put(legacyAsset))
                .put("ignoredFutureRoot", JSONArray().put(1).put(2))
                .put("activeStrokeIds", JSONArray().put("legacy-stroke"))
                .put("appliedOperationIds", JSONArray().put("legacy-operation"))
                .put("revision", 9L)
                .put("pageNumber", PAGE)
                .put("bookId", BOOK_ID)
                .put("formatVersion", ANNOTATION_FORMAT_VERSION)
            pageDirectory.resolve("checkpoint.json").writeText(legacyRoot.toString(), Charsets.UTF_8)

            val loaded = PageOperationLogStore(root).loadPage(BOOK_ID, PAGE)

            assertEquals(9L, loaded.revision)
            assertEquals(setOf(StrokeId("legacy-stroke")), loaded.activeStrokeIds)
            assertEquals(setOf(OperationId("legacy-operation")), loaded.appliedOperationIds)
            val asset = loaded.assets.getValue(StrokeId("legacy-stroke"))
            assertEquals(2, asset.points.size)
            assertEquals(1f, asset.points[0].pressure, 0f)
            assertEquals(0.4f, asset.points[1].pressure, 0f)
            assertNull(asset.itemId)
            assertNull(asset.publishedAtEpochMillis)
            assertNull(asset.parentStrokeId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun streamedCheckpointKeepsLegacyJsonShapeAndAtomicallyReplacesPriorFile() {
        val root = Files.createTempDirectory("masternote-checkpoint-shape").toFile()
        try {
            val strokeId = StrokeId("streamed-stroke")
            val parentId = StrokeId("streamed-parent")
            val operationId = OperationId("streamed-operation")
            val asset = StrokeAsset(
                id = strokeId,
                pageNumber = PAGE,
                tool = StrokeTool.HIGHLIGHTER,
                colorArgb = 0x80123456.toInt(),
                width = 6.25f,
                points = listOf(PagePoint(1.25f, 2.5f, 0.75f)),
                authorId = "teacher",
                attemptNo = 4,
                logicalClock = 81L,
                deviceId = "teacher-device",
                itemId = "problem-7",
                publishedAtEpochMillis = 9876L,
                bounds = PageBounds(1.25f, 2.5f, 1.25f, 2.5f),
                createdAtEpochMillis = 8765L,
                parentStrokeId = parentId,
            )
            val first = AnnotationSnapshot(
                bookId = BOOK_ID,
                pageNumber = PAGE,
                revision = 10L,
                assets = linkedMapOf(strokeId to asset),
                activeStrokeIds = linkedSetOf(strokeId),
                appliedOperationIds = linkedSetOf(operationId),
            )
            val store = PageOperationLogStore(root)
            store.writeCheckpoint(first)
            store.writeCheckpoint(
                AnnotationSnapshot(
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    revision = 11L,
                    assets = first.assets,
                    activeStrokeIds = first.activeStrokeIds,
                    appliedOperationIds = first.appliedOperationIds,
                ),
            )

            val pageDirectory = root.resolve("$BOOK_ID/pages/$PAGE")
            val checkpoint = pageDirectory.resolve("checkpoint.json")
            assertTrue(checkpoint.isFile)
            assertFalse(pageDirectory.resolve("checkpoint.json.tmp").exists())
            val legacyReader = JSONObject(checkpoint.readText(Charsets.UTF_8))
            assertEquals(ANNOTATION_FORMAT_VERSION, legacyReader.getInt("formatVersion"))
            assertEquals(BOOK_ID, legacyReader.getString("bookId"))
            assertEquals(PAGE, legacyReader.getInt("pageNumber"))
            assertEquals(11L, legacyReader.getLong("revision"))
            assertEquals("streamed-stroke", legacyReader.getJSONArray("activeStrokeIds").getString(0))
            assertEquals("streamed-operation", legacyReader.getJSONArray("appliedOperationIds").getString(0))
            val legacyAsset = legacyReader.getJSONArray("assets").getJSONObject(0)
            assertEquals("problem-7", legacyAsset.getString("itemId"))
            assertEquals(9876L, legacyAsset.getLong("publishedAt"))
            assertEquals("streamed-parent", legacyAsset.getString("parentStrokeId"))
            assertEquals(0.75, legacyAsset.getJSONArray("points").getJSONArray(0).getDouble(2), 0.0)

            val reloaded = PageOperationLogStore(root).loadPage(BOOK_ID, PAGE)
            assertEquals(11L, reloaded.revision)
            assertEquals(asset, reloaded.assets.getValue(strokeId))
            assertEquals(setOf(strokeId), reloaded.activeStrokeIds)
            assertEquals(setOf(operationId), reloaded.appliedOperationIds)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedCheckpointIsStillQuarantined() {
        val root = Files.createTempDirectory("masternote-checkpoint-corrupt").toFile()
        try {
            val pageDirectory = root.resolve("$BOOK_ID/pages/$PAGE").apply { mkdirs() }
            pageDirectory.resolve("checkpoint.json").writeText("{\"formatVersion\":", Charsets.UTF_8)

            val failure = runCatching { PageOperationLogStore(root).loadPage(BOOK_ID, PAGE) }.exceptionOrNull()

            assertTrue(failure is CorruptAnnotationDataException)
            assertFalse(pageDirectory.resolve("checkpoint.json").exists())
            assertNotNull(pageDirectory.listFiles()?.singleOrNull {
                it.name.startsWith("checkpoint.json.corrupt-")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedStreamedWriteKeepsPreviousCheckpointAndRemovesOnlyTemporaryFile() {
        val root = Files.createTempDirectory("masternote-checkpoint-write-failure").toFile()
        try {
            val validAsset = StrokeAsset(
                id = StrokeId("valid-stroke"),
                pageNumber = PAGE,
                tool = StrokeTool.PEN,
                colorArgb = 0xFF112233.toInt(),
                width = 2f,
                points = listOf(PagePoint(1f, 2f)),
                logicalClock = 1L,
                deviceId = "device",
                createdAtEpochMillis = 1L,
            )
            val validSnapshot = AnnotationSnapshot(
                bookId = BOOK_ID,
                pageNumber = PAGE,
                revision = 1L,
                assets = mapOf(validAsset.id to validAsset),
                activeStrokeIds = setOf(validAsset.id),
            )
            val store = PageOperationLogStore(root)
            store.writeCheckpoint(validSnapshot)
            val checkpoint = root.resolve("$BOOK_ID/pages/$PAGE/checkpoint.json")
            val durableBytes = checkpoint.readBytes()
            val invalidAsset = validAsset.copy(
                id = StrokeId("invalid-stroke"),
                points = listOf(PagePoint(Float.NaN, 2f)),
            )

            assertThrows(IllegalArgumentException::class.java) {
                store.writeCheckpoint(
                    AnnotationSnapshot(
                        bookId = BOOK_ID,
                        pageNumber = PAGE,
                        revision = 2L,
                        assets = mapOf(invalidAsset.id to invalidAsset),
                        activeStrokeIds = setOf(invalidAsset.id),
                    ),
                )
            }

            assertArrayEquals(durableBytes, checkpoint.readBytes())
            assertFalse(requireNotNull(checkpoint.parentFile).resolve("checkpoint.json.tmp").exists())
            assertEquals(1L, PageOperationLogStore(root).loadPage(BOOK_ID, PAGE).revision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(timeout = 120_000L)
    fun roundTripsTwentyToFortyMegabyteCheckpointInNinetySixMegabyteHeap() {
        val root = Files.createTempDirectory("masternote-checkpoint-low-heap").toFile()
        try {
            val javaExecutable = File(
                requireNotNull(System.getProperty("java.home")),
                if (requireNotNull(System.getProperty("os.name")).startsWith("Windows", ignoreCase = true)) {
                    "bin/java.exe"
                } else {
                    "bin/java"
                },
            )
            val explicitClasspath = listOf(
                LargeCheckpointLowHeapProbe::class.java,
                PageOperationLogStore::class.java,
                AnnotationSnapshot::class.java,
                AnnotationDocument::class.java,
                JsonReader::class.java,
                JSONObject::class.java,
                Context::class.java,
                Unit::class.java,
            ).map { type ->
                val codeSource = requireNotNull(type.protectionDomain?.codeSource) {
                    "No code source for ${type.name}"
                }
                File(codeSource.location.toURI()).absolutePath
            }
            val classpath = (listOf(requireNotNull(System.getProperty("java.class.path"))) + explicitClasspath)
                .distinct()
                .joinToString(File.pathSeparator)
            val process = ProcessBuilder(
                javaExecutable.absolutePath,
                "-Xms32m",
                "-Xmx96m",
                "-cp",
                classpath,
                LargeCheckpointLowHeapProbe::class.java.name,
                root.absolutePath,
            ).redirectErrorStream(true).start()
            val finished = process.waitFor(110L, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertTrue("Low-heap checkpoint writer timed out: $output", finished)
            assertEquals("Low-heap checkpoint writer failed: $output", 0, process.exitValue())
            val checkpoint = root.resolve("$BOOK_ID/pages/$PAGE/checkpoint.json")
            assertTrue(checkpoint.isFile)
            assertTrue("Checkpoint was only ${checkpoint.length()} bytes", checkpoint.length() >= 20L * MIB)
            assertTrue("Checkpoint was ${checkpoint.length()} bytes", checkpoint.length() <= 40L * MIB)
            assertFalse(requireNotNull(checkpoint.parentFile).resolve("checkpoint.json.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val BOOK_ID = "checkpoint-book"
        const val PAGE = 7
        const val MIB = 1024 * 1024
    }
}

/** Runs outside Gradle's test worker so the regression has a real, bounded heap. */
object LargeCheckpointLowHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(requireNotNull(args.singleOrNull()) { "Expected checkpoint root path" })
        val points = List(8_192) { index ->
            PagePoint(index + 0.125f, index + 0.375f, 0.75f)
        }
        val assets = linkedMapOf<StrokeId, StrokeAsset>()
        repeat(128) { index ->
            val id = StrokeId("large-stroke-$index")
            assets[id] = StrokeAsset(
                id = id,
                pageNumber = 7,
                tool = StrokeTool.PEN,
                colorArgb = 0xFF17233C.toInt(),
                width = 3.25f,
                points = points,
                authorId = "student",
                attemptNo = 1,
                logicalClock = index.toLong(),
                deviceId = "large-checkpoint-device",
                bounds = PageBounds(0.125f, 0.375f, 8191.125f, 8191.375f),
                createdAtEpochMillis = index.toLong(),
            )
        }
        PageOperationLogStore(root).writeCheckpoint(
            AnnotationSnapshot(
                bookId = "checkpoint-book",
                pageNumber = 7,
                revision = 128L,
                assets = assets,
                activeStrokeIds = emptySet(),
                appliedOperationIds = emptySet(),
            ),
        )
        val checkpoint = root.resolve("checkpoint-book/pages/7/checkpoint.json")
        check(checkpoint.isFile) { "Checkpoint was not committed" }
        val loaded = PageOperationLogStore(root).loadPage("checkpoint-book", 7)
        check(loaded.assets.size == 128) { "Large checkpoint asset count changed" }
        check(loaded.assets.values.sumOf { it.points.size.toLong() } == 128L * 8_192L) {
            "Large checkpoint point count changed"
        }
        println("checkpointBytes=${checkpoint.length()}")
    }
}
