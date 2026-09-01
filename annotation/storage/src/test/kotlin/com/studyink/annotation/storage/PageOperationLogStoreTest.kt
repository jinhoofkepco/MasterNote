package com.studyink.annotation.storage

import com.studyink.annotation.engine.AnnotationChange
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.OperationId
import com.studyink.core.model.PageBounds
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import com.studyink.core.model.LosslessF32PagePointCodec
import com.studyink.core.model.teacherReviewMarkGroupsSha256
import java.util.Base64
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class PageOperationLogStoreTest {
    @Test
    fun compactLocalLogAndNegotiatedExportsRoundTripWithoutChangingSemanticDigest() {
        val sourceRoot = Files.createTempDirectory("masternote-compact-operation-source").toFile()
        val compactTargetRoot = Files.createTempDirectory("masternote-compact-operation-target").toFile()
        val legacyTargetRoot = Files.createTempDirectory("masternote-legacy-operation-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            val asset = stroke("student-device").copy(
                points = listOf(
                    PagePoint(10f, 20f),
                    PagePoint(10.0625f, 19.9375f),
                    PagePoint(10.125f, 19.9375f),
                ),
            )
            source.append(AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE)).addStroke(asset))

            val durableLine = source.operationLogFile(BOOK_ID, PAGE).readLines().single()
            assertTrue(durableLine.contains("\"pointsQ16\""))
            assertFalse(durableLine.contains("\"points\":"))

            val compact = source.encodedStudentOperationsAfter(
                BOOK_ID,
                PAGE,
                "student-device",
                0L,
                AnnotationPointEncoding.COMPACT_Q16_DELTA,
            ).single()
            val legacy = source.encodedStudentOperationsAfter(
                BOOK_ID,
                PAGE,
                "student-device",
                0L,
                AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
            ).single()
            assertTrue(compact.size < legacy.size)
            assertTrue(compact.toString(Charsets.UTF_8).contains("\"pointsQ16\""))
            assertTrue(legacy.toString(Charsets.UTF_8).contains("\"points\":"))

            val compactTarget = PageOperationLogStore(compactTargetRoot)
            compactTarget.appendEncodedStudentOperation(BOOK_ID, PAGE, compact)
            val legacyTarget = PageOperationLogStore(legacyTargetRoot)
            legacyTarget.appendEncodedStudentOperation(BOOK_ID, PAGE, legacy)

            assertEquals(source.loadPage(BOOK_ID, PAGE).activeStrokes, compactTarget.loadPage(BOOK_ID, PAGE).activeStrokes)
            assertEquals(source.loadPage(BOOK_ID, PAGE).activeStrokes, legacyTarget.loadPage(BOOK_ID, PAGE).activeStrokes)
            assertEquals(source.studentLayerSha256(BOOK_ID, PAGE), compactTarget.studentLayerSha256(BOOK_ID, PAGE))
            assertEquals(source.studentLayerSha256(BOOK_ID, PAGE), legacyTarget.studentLayerSha256(BOOK_ID, PAGE))
        } finally {
            sourceRoot.deleteRecursively()
            compactTargetRoot.deleteRecursively()
            legacyTargetRoot.deleteRecursively()
        }
    }

    @Test
    fun compactRequestUsesBitExactF32FallbackForRealisticLegacyInkUnderWireLimit() {
        val sourceRoot = Files.createTempDirectory("masternote-lossless-large-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-lossless-large-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            val legacyPoints = List(17_462) { index ->
                PagePoint(
                    x = index.toFloat() * 0.101_003f + 0.000_17f,
                    y = (index % 997).toFloat() * 0.203_007f + 0.000_31f,
                    pressure = 0.15f + (index % 71).toFloat() / 100f,
                )
            }
            source.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE)).addStroke(
                    stroke("legacy-device").copy(
                        id = StrokeId("large-legacy-stroke"),
                        points = legacyPoints,
                        bounds = PageBounds.from(legacyPoints),
                    ),
                ),
            )

            val compact = source.encodedStudentOperationsAfter(
                BOOK_ID,
                PAGE,
                "legacy-device",
                0L,
                AnnotationPointEncoding.COMPACT_Q16_DELTA,
            ).single()
            val legacy = source.encodedStudentOperationsAfter(
                BOOK_ID,
                PAGE,
                "legacy-device",
                0L,
                AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
            ).single()

            assertTrue(compact.size < 512 * 1024)
            assertTrue(compact.toString(Charsets.UTF_8).contains("\"pointsF32Gzip\""))
            assertFalse(compact.toString(Charsets.UTF_8).contains("\"points\":"))
            assertTrue(legacy.toString(Charsets.UTF_8).contains("\"points\":"))
            assertFalse(legacy.toString(Charsets.UTF_8).contains("\"pointsF32Gzip\""))

            val target = PageOperationLogStore(targetRoot)
            target.appendEncodedStudentOperation(BOOK_ID, PAGE, compact)
            assertPointBitsEqual(
                legacyPoints,
                target.loadPage(BOOK_ID, PAGE).activeStrokes.single().points,
            )
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun compactOperationWith121LosslessAssetsStaysUnderWireLimit() {
        val sourceRoot = Files.createTempDirectory("masternote-lossless-many-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-lossless-many-target").toFile()
        try {
            val deviceId = "many-assets-device"
            val assets = List(121) { assetIndex ->
                val points = List(81) { pointIndex ->
                    val seed = assetIndex * 81 + pointIndex
                    PagePoint(
                        x = seed.toFloat() * 0.113_013f + 0.000_11f,
                        y = (seed % 503).toFloat() * 0.197_021f + 0.000_29f,
                        pressure = 0.2f + (seed % 61).toFloat() / 100f,
                    )
                }
                stroke(deviceId).copy(
                    id = StrokeId("many-$assetIndex"),
                    points = points,
                    bounds = PageBounds.from(points),
                    logicalClock = 1L,
                )
            }
            val operation = AssetOperation(
                id = OperationId("many-assets-operation"),
                addedStrokeIds = assets.mapTo(linkedSetOf(), StrokeAsset::id),
                removedStrokeIds = emptySet(),
                logicalClock = 1L,
                deviceId = deviceId,
            )
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            source.append(
                AnnotationChange(
                    snapshot = AnnotationSnapshot.empty(BOOK_ID, PAGE),
                    operation = operation,
                    addedAssets = assets,
                ),
            )

            val compact = source.encodedStudentOperationsAfter(
                BOOK_ID,
                PAGE,
                deviceId,
                0L,
                AnnotationPointEncoding.COMPACT_Q16_DELTA,
            ).single()
            assertTrue(compact.size < 512 * 1024)
            assertEquals(
                121,
                JSONObject(compact.toString(Charsets.UTF_8)).getJSONArray("addedAssets")
                    .let { encodedAssets ->
                        (0 until encodedAssets.length()).count { index ->
                            encodedAssets.getJSONObject(index).has("pointsF32Gzip")
                        }
                    },
            )

            val target = PageOperationLogStore(targetRoot)
            target.appendEncodedStudentOperation(BOOK_ID, PAGE, compact)
            val received = target.loadPage(BOOK_ID, PAGE).assets
            assets.forEach { expected ->
                assertPointBitsEqual(expected.points, received.getValue(expected.id).points)
            }
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun oneOperationMayMixLegacyQ16AndLosslessF32PointFields() {
        val sourceRoot = Files.createTempDirectory("masternote-mixed-points-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-mixed-points-target").toFile()
        try {
            val deviceId = "mixed-device"
            val assets = listOf(
                stroke(deviceId).copy(
                    id = StrokeId("mixed-q16"),
                    points = listOf(PagePoint(1f, 2f), PagePoint(1.0625f, 2.125f)),
                    logicalClock = 1L,
                ),
                stroke(deviceId).copy(
                    id = StrokeId("mixed-f32"),
                    points = listOf(PagePoint(3.1f, 4.2f, 0.37f), PagePoint(5.3f, 6.4f, 0.83f)),
                    logicalClock = 1L,
                ),
                stroke(deviceId).copy(
                    id = StrokeId("mixed-legacy"),
                    points = listOf(PagePoint(7.1f, 8.2f, 0.41f), PagePoint(9.3f, 10.4f, 0.79f)),
                    logicalClock = 1L,
                ),
            )
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            source.append(
                AnnotationChange(
                    snapshot = AnnotationSnapshot.empty(BOOK_ID, PAGE),
                    operation = AssetOperation(
                        id = OperationId("mixed-operation"),
                        removedStrokeIds = emptySet(),
                        addedStrokeIds = assets.mapTo(linkedSetOf(), StrokeAsset::id),
                        logicalClock = 1L,
                        deviceId = deviceId,
                    ),
                    addedAssets = assets,
                ),
            )
            val root = JSONObject(
                source.encodedStudentOperationsAfter(
                    BOOK_ID,
                    PAGE,
                    deviceId,
                    0L,
                    AnnotationPointEncoding.COMPACT_Q16_DELTA,
                ).single().toString(Charsets.UTF_8),
            )
            val encodedAssets = root.getJSONArray("addedAssets")
            val encodedById = (0 until encodedAssets.length()).associate { index ->
                encodedAssets.getJSONObject(index).let { it.getString("id") to it }
            }
            assertTrue(encodedById.getValue("mixed-q16").has("pointsQ16"))
            assertTrue(encodedById.getValue("mixed-f32").has("pointsF32Gzip"))
            encodedById.getValue("mixed-legacy").apply {
                remove("pointsF32Gzip")
                remove("pointCount")
                put("points", JSONArray().apply {
                    assets.single { it.id.value == "mixed-legacy" }.points.forEach { point ->
                        put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble())
                            .put(point.pressure.toDouble()))
                    }
                })
            }

            val target = PageOperationLogStore(targetRoot)
            target.appendEncodedStudentOperation(
                BOOK_ID,
                PAGE,
                root.toString().toByteArray(Charsets.UTF_8),
            )
            val received = target.loadPage(BOOK_ID, PAGE).assets
            assets.forEach { expected ->
                assertPointBitsEqual(expected.points, received.getValue(expected.id).points)
            }
            assertEquals(source.studentLayerSha256(BOOK_ID, PAGE), target.studentLayerSha256(BOOK_ID, PAGE))
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun compactPointCountsUseOperationAndPublishedCheckpointLimitsBeforeDecode() {
        val sourceRoot = Files.createTempDirectory("masternote-compact-limits-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-compact-limits-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            val points = listOf(PagePoint(1.1f, 2.2f, 0.3f), PagePoint(3.3f, 4.4f, 0.7f))
            source.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE)).addStroke(
                    stroke("limit-device").copy(
                        id = StrokeId("limit-student"),
                        points = points,
                        bounds = PageBounds.from(points),
                    ),
                ),
            )
            val operation = JSONObject(
                source.encodedStudentOperationsAfter(
                    BOOK_ID,
                    PAGE,
                    "limit-device",
                    0L,
                    AnnotationPointEncoding.COMPACT_Q16_DELTA,
                ).single().toString(Charsets.UTF_8),
            )
            val operationAsset = operation.getJSONArray("addedAssets").getJSONObject(0)
            operationAsset.put("pointCount", 32_769)
            assertThrows(IllegalArgumentException::class.java) {
                PageOperationLogStore(targetRoot).appendEncodedStudentOperation(
                    BOOK_ID,
                    PAGE,
                    operation.toString().toByteArray(Charsets.UTF_8),
                )
            }

            operationAsset.put("pointCount", 1)
            operationAsset.put(
                "pointsF32Gzip",
                Base64.getEncoder().encodeToString(LosslessF32PagePointCodec.encode(points)),
            )
            assertThrows(IllegalArgumentException::class.java) {
                PageOperationLogStore(targetRoot).appendEncodedStudentOperation(
                    BOOK_ID,
                    PAGE,
                    operation.toString().toByteArray(Charsets.UTF_8),
                )
            }

            val teacherPoints = listOf(PagePoint(5f, 6f), PagePoint(5.0625f, 6.125f))
            val teacherSource = PageOperationLogStore(sourceRoot.resolve("teacher"), 10_000)
            teacherSource.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE)).addStroke(
                    stroke("teacher-device").copy(
                        id = StrokeId("limit-teacher"),
                        authorId = "teacher",
                        attemptNo = 1,
                        publishedAtEpochMillis = 1_000L,
                        points = teacherPoints,
                        bounds = PageBounds.from(teacherPoints),
                    ),
                ),
            )
            val teacherCheckpoint = JSONObject(
                teacherSource.encodePublishedTeacherLayerCheckpoint(
                    BOOK_ID,
                    PAGE,
                    1,
                    AnnotationPointEncoding.COMPACT_Q16_DELTA,
                ).toString(Charsets.UTF_8),
            )
            teacherCheckpoint.getJSONArray("assets").getJSONObject(0).put("pointCount", 8_193)
            assertThrows(IllegalArgumentException::class.java) {
                PageOperationLogStore(targetRoot.resolve("teacher-target"))
                    .applyPublishedTeacherLayerCheckpoint(
                        localBookId = BOOK_ID,
                        pageNumber = PAGE,
                        attemptNo = 1,
                        checkpointBytes = teacherCheckpoint.toString().toByteArray(Charsets.UTF_8),
                    )
            }
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun compactAndLegacyCheckpointsHaveTheSameIdentityAndMixedLocalAssetsRemainExact() {
        val sourceRoot = Files.createTempDirectory("masternote-compact-checkpoint-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-compact-checkpoint-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            var snapshot = AnnotationSnapshot.empty(BOOK_ID, PAGE)
            val compactEligible = stroke("student-device").copy(
                id = StrokeId("compact-eligible"),
                points = listOf(PagePoint(1f, 2f), PagePoint(1.0625f, 2f)),
            )
            val legacyExact = stroke("student-device").copy(
                id = StrokeId("legacy-pressure"),
                points = listOf(PagePoint(3.125f, 4.25f, 0.4f)),
            )
            snapshot = source.append(AnnotationDocument(snapshot).addStroke(compactEligible))
            snapshot = source.append(AnnotationDocument(snapshot).addStroke(legacyExact))
            source.writeCheckpoint(snapshot)

            val localCheckpoint = JSONObject(
                sourceRoot.resolve("$BOOK_ID/pages/$PAGE/checkpoint.json").readText(Charsets.UTF_8)
            )
            val assets = localCheckpoint.getJSONArray("assets")
            val byId = (0 until assets.length()).associate { index ->
                assets.getJSONObject(index).let { it.getString("id") to it }
            }
            assertTrue(byId.getValue("compact-eligible").has("pointsQ16"))
            assertFalse(byId.getValue("compact-eligible").has("points"))
            assertTrue(byId.getValue("legacy-pressure").has("pointsF32Gzip"))
            assertFalse(byId.getValue("legacy-pressure").has("points"))

            val restarted = PageOperationLogStore(sourceRoot).loadPage(BOOK_ID, PAGE)
            assertEquals(snapshot.assets, restarted.assets)

            val compact = source.exportStudentLayerCheckpoint(
                BOOK_ID,
                PAGE,
                "student-device",
                AnnotationPointEncoding.COMPACT_Q16_DELTA,
            )
            val legacy = source.exportStudentLayerCheckpoint(
                BOOK_ID,
                PAGE,
                "student-device",
                AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
            )
            val compactJson = JSONObject(compact.copyCheckpointBytes().toString(Charsets.UTF_8))
            val legacyJson = JSONObject(legacy.copyCheckpointBytes().toString(Charsets.UTF_8))
            assertEquals(legacyJson.getString("checkpointId"), compactJson.getString("checkpointId"))
            assertEquals(legacy.layerSha256, compact.layerSha256)

            val applied = PageOperationLogStore(targetRoot).applyStudentLayerCheckpoint(
                BOOK_ID,
                PAGE,
                compact.copyCheckpointBytes(),
                expectedResultLayerSha256 = compact.layerSha256,
            )
            assertEquals(compact.layerSha256, applied.layerSha256)
            assertEquals(snapshot.activeStrokes, applied.snapshot.activeStrokes)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun boundedDeltaCopiesOnlyThePrefixThatFitsItsWireBudget() {
        val root = Files.createTempDirectory("masternote-bounded-delta").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            var snapshot = AnnotationSnapshot.empty(BOOK_ID, PAGE)
            repeat(3) {
                snapshot = store.append(AnnotationDocument(snapshot).addStroke(stroke("student-device")))
            }
            val all = store.encodedOperationsAfter(BOOK_ID, PAGE, "student-device", 0L)
            val oneFrameBudget = 8 + 4 + all.first().size

            val bounded = store.encodedOperationsAfterBounded(
                BOOK_ID,
                PAGE,
                "student-device",
                0L,
                maxFramedBytes = oneFrameBudget,
                fixedFrameBytes = 8,
                perOperationFrameBytes = 4,
            )

            assertFalse(bounded.complete)
            assertEquals(oneFrameBudget, bounded.framedByteCount)
            assertEquals(1, bounded.operations.size)
            assertArrayEquals(all.first(), bounded.operations.single())
            assertEquals(store.operationCursor(all.first()).logicalClock, bounded.lastLogicalClock)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportedRecordDoesNotRetainCallerMutablePointList() {
        val sourceRoot = Files.createTempDirectory("masternote-immutable-record-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-immutable-record-target").toFile()
        try {
            val callerPoints = mutableListOf(PagePoint(1.1f, 2.2f, 0.3f), PagePoint(3.3f, 4.4f, 0.7f))
            val expected = callerPoints.toList()
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            source.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE)).addStroke(
                    stroke("immutable-device").copy(points = callerPoints),
                ),
            )
            callerPoints[0] = PagePoint(900f, 901f, 0.99f)

            val encoded = source.encodedStudentOperationsAfter(
                BOOK_ID,
                PAGE,
                "immutable-device",
                0L,
                AnnotationPointEncoding.COMPACT_Q16_DELTA,
            ).single()
            val target = PageOperationLogStore(targetRoot)
            target.appendEncodedStudentOperation(BOOK_ID, PAGE, encoded)

            assertPointBitsEqual(expected, target.loadPage(BOOK_ID, PAGE).activeStrokes.single().points)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun vmErrorEscapesCorruptionBoundaryWithoutRunningQuarantineHandler() {
        var quarantined = false
        val fatal = OutOfMemoryError("simulated")

        val thrown = assertThrows(OutOfMemoryError::class.java) {
            readAnnotationDataOrHandleCorruption<Unit>(
                read = { throw fatal },
                onCorruption = {
                    quarantined = true
                },
            )
        }

        assertTrue(thrown === fatal)
        assertFalse(quarantined)
    }

    @Test
    fun durableAppendSurvivesReloadWithoutAForcedCheckpoint() {
        val root = Files.createTempDirectory("masternote-submit-without-checkpoint").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            val added = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                .addStroke(stroke("student-device"))

            store.append(added)

            assertFalse(root.resolve("$BOOK_ID/pages/$PAGE/checkpoint.json").exists())
            val reloaded = PageOperationLogStore(root, checkpointInterval = 10_000)
                .loadPage(BOOK_ID, PAGE)
            assertEquals(1L, reloaded.revision)
            assertEquals(added.addedAssets.map(StrokeAsset::id).toSet(), reloaded.activeStrokeIds)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pageIndexCacheIsBoundedAndAnEvictedPageRebuildsFromDurableLog() {
        val root = Files.createTempDirectory("masternote-page-index-lru").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            val firstPage = 0
            val added = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, firstPage))
                .addStroke(stroke("student-device").copy(pageNumber = firstPage))
            store.append(added)

            (1..PageOperationLogStore.MAX_CACHED_PAGE_INDEXES).forEach { page ->
                store.loadPage(BOOK_ID, page)
            }

            assertEquals(PageOperationLogStore.MAX_CACHED_PAGE_INDEXES, store.cachedPageIndexCount())
            assertFalse(store.isPageIndexCached(BOOK_ID, firstPage))
            val rebuilt = store.loadPage(BOOK_ID, firstPage)
            assertEquals(1L, rebuilt.revision)
            assertEquals(added.addedAssets.map(StrokeAsset::id).toSet(), rebuilt.activeStrokeIds)
            assertEquals(PageOperationLogStore.MAX_CACHED_PAGE_INDEXES, store.cachedPageIndexCount())
            assertTrue(store.isPageIndexCached(BOOK_ID, firstPage))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun studentDigestCacheIsBoundedAndNeverMasksANewerRevision() {
        val root = Files.createTempDirectory("masternote-student-digest-lru").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            repeat(PageOperationLogStore.MAX_CACHED_STUDENT_LAYER_DIGESTS + 1) { page ->
                store.append(
                    AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, page))
                        .addStroke(stroke("student-device").copy(pageNumber = page)),
                )
                store.studentLayerSha256(BOOK_ID, page)
            }
            assertEquals(
                PageOperationLogStore.MAX_CACHED_STUDENT_LAYER_DIGESTS,
                store.cachedStudentLayerDigestCount(),
            )

            val before = store.studentLayerSha256(BOOK_ID, 0)
            val current = store.loadPage(BOOK_ID, 0)
            store.append(
                AnnotationDocument(current).addStroke(
                    stroke("student-device").copy(
                        pageNumber = 0,
                        points = listOf(PagePoint(10f, 10f), PagePoint(20f, 20f)),
                    ),
                ),
            )
            assertFalse(before == store.studentLayerSha256(BOOK_ID, 0))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publishedTeacherDigestCacheUsesExactLayerStructureAcrossStudentWritesAndRestore() {
        val root = Files.createTempDirectory("masternote-published-teacher-digest-cache").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            val teacher = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            store.append(teacher.addStroke(
                stroke("teacher-device").copy(authorId = "teacher", attemptNo = 1),
            ))
            store.append(requireNotNull(teacher.publishTeacherDrafts(1, "teacher-device")))

            val before = store.publishedTeacherLayerDigestMaterializationCount()
            val first = store.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1)
            assertEquals(before + 1L, store.publishedTeacherLayerDigestMaterializationCount())
            assertEquals(first, store.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))
            assertEquals(before + 1L, store.publishedTeacherLayerDigestMaterializationCount())

            // The digest cache must answer before touching the much smaller decoded-page LRU.
            (1..PageOperationLogStore.MAX_CACHED_PAGE_INDEXES).forEach { offset ->
                val otherPage = PAGE + offset
                store.loadPage(BOOK_ID, otherPage)
            }
            assertFalse(store.isPageIndexCached(BOOK_ID, PAGE))
            assertEquals(first, store.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))
            assertFalse(store.isPageIndexCached(BOOK_ID, PAGE))
            assertEquals(before + 1L, store.publishedTeacherLayerDigestMaterializationCount())

            val student = AnnotationDocument(store.loadPage(BOOK_ID, PAGE))
            store.append(student.addStroke(
                stroke("student-device").copy(
                    authorId = "student",
                    attemptNo = 1,
                    points = listOf(PagePoint(30f, 30f), PagePoint(40f, 40f)),
                ),
            ))
            assertEquals(first, store.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))
            assertEquals(before + 1L, store.publishedTeacherLayerDigestMaterializationCount())

            val laterTeacher = AnnotationDocument(store.loadPage(BOOK_ID, PAGE))
            store.append(laterTeacher.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(50f, 50f), PagePoint(60f, 60f)),
                ),
            ))
            store.append(requireNotNull(laterTeacher.publishTeacherDrafts(1, "teacher-device")))
            val changed = store.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1)
            assertNotEquals(first, changed)
            assertEquals(before + 2L, store.publishedTeacherLayerDigestMaterializationCount())

            store.resetCachedStateAfterRestore()
            assertEquals(0, store.cachedPublishedTeacherLayerDigestCount())
            assertEquals(changed, store.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))
            assertEquals(before + 3L, store.publishedTeacherLayerDigestMaterializationCount())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun genericReplicaAndPublishedCheckpointInvalidateStructuralTeacherDigestButStudentCheckpointDoesNot() {
        val sourceRoot = Files.createTempDirectory("masternote-published-cache-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-published-cache-target").toFile()
        val studentRoot = Files.createTempDirectory("masternote-published-cache-student").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            source.append(document.addStroke(
                stroke("teacher-device").copy(authorId = "teacher", attemptNo = 1),
            ))
            source.append(requireNotNull(document.publishTeacherDrafts(1, "teacher-device")))
            val expected = source.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1)

            val target = PageOperationLogStore(targetRoot, checkpointInterval = 10_000)
            val empty = target.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1)
            val beforeReplica = target.publishedTeacherLayerDigestMaterializationCount()
            source.encodedOperationsAfter(BOOK_ID, PAGE, 0L).forEach { encoded ->
                target.appendEncodedOperation(BOOK_ID, PAGE, encoded)
            }
            assertEquals(expected, target.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))
            assertNotEquals(empty, expected)
            assertEquals(
                beforeReplica + 1L,
                target.publishedTeacherLayerDigestMaterializationCount(),
            )

            val checkpointRoot =
                Files.createTempDirectory("masternote-published-cache-checkpoint").toFile()
            val checkpointTarget = PageOperationLogStore(checkpointRoot, checkpointInterval = 10_000)
            try {
                val checkpointEmpty = checkpointTarget.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1)
                val export = source.exportPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1)
                checkpointTarget.applyPublishedTeacherLayerCheckpoint(
                    BOOK_ID,
                    PAGE,
                    1,
                    export.copyCheckpointBytes(),
                    export.layerSha256,
                )
                assertNotEquals(checkpointEmpty, checkpointTarget.publishedTeacherLayerSha256(
                    BOOK_ID, PAGE, 1,
                ))
            } finally {
                checkpointRoot.deleteRecursively()
            }

            val studentSource = PageOperationLogStore(studentRoot, checkpointInterval = 10_000)
            studentSource.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE)).addStroke(
                    stroke("student-device").copy(authorId = "student", attemptNo = 1),
                ),
            )
            val studentCheckpoint = studentSource.exportStudentLayerCheckpoint(
                BOOK_ID,
                PAGE,
                "student-device",
            )
            val beforeStudent = target.publishedTeacherLayerDigestMaterializationCount()
            target.applyStudentLayerCheckpoint(
                BOOK_ID,
                PAGE,
                studentCheckpoint.copyCheckpointBytes(),
                studentCheckpoint.layerSha256,
                listOf(1),
            )
            assertEquals(expected, target.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))
            assertEquals(beforeStudent, target.publishedTeacherLayerDigestMaterializationCount())
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
            studentRoot.deleteRecursively()
        }
    }

    @Test
    fun publishedTeacherDigestStructuralCacheIsBounded() {
        val root = Files.createTempDirectory("masternote-published-teacher-digest-lru").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            repeat(PageOperationLogStore.MAX_CACHED_PUBLISHED_TEACHER_LAYER_DIGESTS + 1) { page ->
                store.publishedTeacherLayerSha256(BOOK_ID, page, 1)
            }
            assertEquals(
                PageOperationLogStore.MAX_CACHED_PUBLISHED_TEACHER_LAYER_DIGESTS,
                store.cachedPublishedTeacherLayerDigestCount(),
            )
            assertEquals(
                PageOperationLogStore.MAX_CACHED_PUBLISHED_TEACHER_LAYER_DIGESTS,
                store.cachedPublishedTeacherLayerGenerationCount(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changingUnpublishedAncestorInvalidatesPublishedTeacherDigest() {
        val root = Files.createTempDirectory("masternote-published-teacher-parent-cache").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            store.append(document.addStroke(
                stroke("teacher-device").copy(authorId = "teacher", attemptNo = 1),
            ))
            store.append(requireNotNull(document.publishTeacherDrafts(1, "teacher-device")))
            val snapshot = store.loadPage(BOOK_ID, PAGE)
            val published = snapshot.activeStrokeIds.asSequence()
                .mapNotNull(snapshot.assets::get)
                .single { it.authorId == "teacher" && it.publishedAtEpochMillis != null }
            val parent = requireNotNull(published.parentStrokeId).let { requireNotNull(snapshot.assets[it]) }
            assertEquals(null, parent.publishedAtEpochMillis)
            val beforeDigest = store.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1)
            val beforeCount = store.publishedTeacherLayerDigestMaterializationCount()
            val replacementClock = maxOf(parent.logicalClock + 1L, published.logicalClock + 1L)
            val replacement = parent.copy(
                points = listOf(PagePoint(70f, 70f), PagePoint(80f, 80f)),
                logicalClock = replacementClock,
            )
            store.append(AnnotationChange(
                snapshot = snapshot,
                operation = AssetOperation(
                    id = OperationId("replace-unpublished-teacher-parent"),
                    removedStrokeIds = emptySet(),
                    addedStrokeIds = setOf(parent.id),
                    logicalClock = replacementClock,
                    deviceId = "teacher-device",
                ),
                addedAssets = listOf(replacement),
            ))

            assertNotEquals(beforeDigest, store.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))
            assertEquals(beforeCount + 1L, store.publishedTeacherLayerDigestMaterializationCount())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun studentDigestIsIndependentOfCheckpointPointPolicyAndExpandedLimitRoundTrips() {
        val supportedRoot = Files.createTempDirectory("masternote-student-large-stroke").toFile()
        val oversizedRoot = Files.createTempDirectory("masternote-student-digest-only").toFile()
        val targetRoot = Files.createTempDirectory("masternote-student-large-target").toFile()
        try {
            assertEquals(
                8 * (2 * 1024 * 1024 - 32 * 1024),
                PageOperationLogStore.MAX_STUDENT_LAYER_CHECKPOINT_BYTES,
            )
            val supported = PageOperationLogStore(supportedRoot, checkpointInterval = 10_000)
            supported.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE)).addStroke(
                    stroke("student-device").copy(points = largePath(32_768)),
                ),
            )
            val export = supported.exportStudentLayerCheckpoint(BOOK_ID, PAGE, "student-device")
            val target = PageOperationLogStore(targetRoot)
            val applied = target.applyStudentLayerCheckpoint(
                BOOK_ID,
                PAGE,
                export.copyCheckpointBytes(),
                export.layerSha256,
                listOf(1),
            )
            assertEquals(export.layerSha256, applied.layerSha256)
            assertEquals(export.layerSha256, target.studentLayerSha256(BOOK_ID, PAGE))

            val digestOnly = PageOperationLogStore(oversizedRoot, checkpointInterval = 10_000)
            digestOnly.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE)).addStroke(
                    stroke("student-device").copy(points = largePath(32_769)),
                ),
            )
            assertTrue(Regex("[0-9a-f]{64}").matches(digestOnly.studentLayerSha256(BOOK_ID, PAGE)))
            assertThrows(IllegalArgumentException::class.java) {
                digestOnly.exportStudentLayerCheckpoint(BOOK_ID, PAGE, "student-device")
            }
        } finally {
            supportedRoot.deleteRecursively()
            oversizedRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun localAppendSignalsExactlyOnceAfterItIsReadableFromTheStableRoot() {
        val root = Files.createTempDirectory("masternote-commit-local").toFile()
        try {
            val store = PageOperationLogStore(root)
            val change = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                .addStroke(stroke("local-device"))
            val observedRevisions = mutableListOf<Long>()
            val subscription = MasterNoteDataCommitBus.addListener {
                store.withStableDataRoot {
                    observedRevisions += store.loadPage(BOOK_ID, PAGE).revision
                }
            }
            try {
                store.append(change)
                store.append(change)
            } finally {
                subscription.close()
            }

            assertEquals(listOf(1L), observedRevisions)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteAppendSignalsExactlyOnceAndDuplicateReplayDoesNotSignal() {
        val sourceRoot = Files.createTempDirectory("masternote-commit-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-commit-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val target = PageOperationLogStore(targetRoot)
            source.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("remote-device")),
            )
            val encoded = source.encodedOperationsAfter(BOOK_ID, PAGE, 0L).single()
            val observedRevisions = mutableListOf<Long>()
            val subscription = MasterNoteDataCommitBus.addListener {
                observedRevisions += target.loadPage(BOOK_ID, PAGE).revision
            }
            try {
                target.appendEncodedOperation(BOOK_ID, PAGE, encoded)
                target.appendEncodedOperation(BOOK_ID, PAGE, encoded)
            } finally {
                subscription.close()
            }

            assertEquals(listOf(1L), observedRevisions)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun staleLocalChangeMergesWithRemoteOperationAndSurvivesReload() {
        val sourceRoot = Files.createTempDirectory("masternote-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-target").toFile()
        try {
            val sourceStore = PageOperationLogStore(sourceRoot, checkpointInterval = 2)
            val targetStore = PageOperationLogStore(targetRoot, checkpointInterval = 2)
            val empty = AnnotationSnapshot.empty(BOOK_ID, PAGE)

            // Reader creates this change from revision zero, but has not persisted it yet.
            val localStroke = stroke("local-device")
            val staleLocalChange = AnnotationDocument(empty).addStroke(localStroke)

            // Meanwhile LAN persists another operation into the same target page.
            val remoteStroke = stroke("remote-device")
            sourceStore.append(AnnotationDocument(empty).addStroke(remoteStroke))
            val encodedRemote = sourceStore.encodedOperationsAfter(BOOK_ID, PAGE, 0L).single()
            targetStore.appendEncodedOperation(BOOK_ID, PAGE, encodedRemote)

            val merged = targetStore.append(staleLocalChange)

            assertEquals(2L, merged.revision)
            assertTrue(localStroke.id in merged.activeStrokeIds)
            assertTrue(remoteStroke.id in merged.activeStrokeIds)

            // Re-reading the append log must produce the same union, not skip a same-revision row.
            val reloaded = PageOperationLogStore(targetRoot, checkpointInterval = 2)
                .loadPage(BOOK_ID, PAGE)
            assertEquals(2L, reloaded.revision)
            assertEquals(setOf(localStroke.id, remoteStroke.id), reloaded.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun pageStateRestoresClockAdvancedByOperationWithoutAddedAssets() {
        val root = Files.createTempDirectory("masternote-clock").toFile()
        try {
            val store = PageOperationLogStore(root)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val added = document.addStroke(stroke("student-device"))
            store.append(added)
            val erased = document.erase(
                page = PAGE,
                path = added.addedAssets.single().points,
                radius = 20f,
                wholeStroke = true,
                authorId = "student",
                attemptNo = 1,
                deviceId = "student-device",
            )!!
            assertTrue(erased.addedAssets.isEmpty())
            store.append(erased)

            val loaded = PageOperationLogStore(root).loadPageState(BOOK_ID, PAGE)
            val next = AnnotationDocument(
                initial = loaded.snapshot,
                operationClockHighWater = loaded.operationClockHighWater,
            ).addStroke(stroke("student-device"))

            assertEquals(2L, loaded.operationClockHighWater)
            assertEquals(3L, next.operation.logicalClock)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syntheticCheckpointPreservesClockRevisionAndLaterLocalHistoryAcrossRestart() {
        val sourceRoot = Files.createTempDirectory("masternote-synthetic-clock-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-synthetic-clock-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val sourceAdd = sourceDocument.addStroke(stroke("remote-student"))
            source.append(sourceAdd)
            val sourceErase = requireNotNull(
                sourceDocument.erase(
                    page = PAGE,
                    path = sourceAdd.addedAssets.single().points,
                    radius = 20f,
                    wholeStroke = true,
                    authorId = "student",
                    attemptNo = 1,
                    deviceId = "remote-student",
                ),
            )
            assertTrue(sourceErase.addedAssets.isEmpty())
            source.append(sourceErase)
            val exported = source.exportStudentLayerCheckpoint(BOOK_ID, PAGE, "remote-student")
            assertEquals(2L, exported.originDeviceHighWater)

            val target = PageOperationLogStore(targetRoot, checkpointInterval = 10_000)
            val targetDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            target.append(targetDocument.addStroke(stroke("local-device")))
            val logBytesBeforeCheckpoint = target.operationLogFile(BOOK_ID, PAGE).length()

            val applied = target.applyStudentLayerCheckpoint(
                localBookId = BOOK_ID,
                pageNumber = PAGE,
                checkpointBytes = exported.copyCheckpointBytes(),
                expectedResultLayerSha256 = exported.layerSha256,
            )
            assertEquals(2L, applied.snapshot.revision)
            assertEquals(logBytesBeforeCheckpoint, target.operationLogFile(BOOK_ID, PAGE).length())
            assertEquals(2L, target.loadPageState(BOOK_ID, PAGE).operationClockHighWater)
            assertEquals(2L, target.maxOperationClock(BOOK_ID, PAGE, "student-layer-checkpoint"))

            val restarted = PageOperationLogStore(targetRoot, checkpointInterval = 10_000)
            val durable = restarted.loadPageState(BOOK_ID, PAGE)
            assertEquals(applied.snapshot.revision, durable.snapshot.revision)
            assertEquals(applied.snapshot.assets, durable.snapshot.assets)
            assertEquals(applied.snapshot.activeStrokeIds, durable.snapshot.activeStrokeIds)
            assertEquals(applied.snapshot.appliedOperationIds, durable.snapshot.appliedOperationIds)
            assertEquals(2L, durable.operationClockHighWater)
            assertEquals(2L, restarted.maxOperationClock(BOOK_ID, PAGE, "student-layer-checkpoint"))

            val localDocument = AnnotationDocument(durable.snapshot, durable.operationClockHighWater)
            val localAdd = localDocument.addStroke(
                stroke("local-device").copy(authorId = "teacher", publishedAtEpochMillis = 100L),
            )
            assertEquals(3L, localAdd.operation.logicalClock)
            restarted.append(localAdd)
            val localUndo = requireNotNull(localDocument.undo("local-device"))
            assertEquals(4L, localUndo.operation.logicalClock)
            val afterUndo = restarted.append(localUndo)
            assertEquals(4L, afterUndo.revision)

            val localDelta = restarted.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "local-device",
                1L,
            )
            assertEquals(listOf(3L, 4L), localDelta.map { restarted.operationCursor(it).logicalClock })

            val secondRestart = PageOperationLogStore(targetRoot, checkpointInterval = 10_000)
            val finalPage = secondRestart.loadPageState(BOOK_ID, PAGE)
            assertEquals(afterUndo.revision, finalPage.snapshot.revision)
            assertEquals(afterUndo.assets, finalPage.snapshot.assets)
            assertEquals(afterUndo.activeStrokeIds, finalPage.snapshot.activeStrokeIds)
            assertEquals(afterUndo.appliedOperationIds, finalPage.snapshot.appliedOperationIds)
            assertEquals(4L, finalPage.operationClockHighWater)
            assertEquals(4L, finalPage.snapshot.revision)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun redoOfUnpublishedTeacherDraftIsNotEligibleForSync() {
        val root = Files.createTempDirectory("masternote-draft-redo").toFile()
        try {
            val store = PageOperationLogStore(root)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val draft = stroke("teacher-device").copy(
                authorId = "teacher",
                publishedAtEpochMillis = null,
            )
            store.append(document.addStroke(draft))
            store.append(document.undo("teacher-device")!!)
            store.append(document.redo("teacher-device")!!)

            val publishable = store.encodedOperationsAfter(
                bookId = BOOK_ID,
                pageNumber = PAGE,
                originDeviceId = "teacher-device",
                logicalClock = 0L,
                includeTeacherDrafts = false,
            )

            assertTrue(publishable.isEmpty())
            assertFalse(store.loadPage(BOOK_ID, PAGE).activeStrokes.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun studentCheckpointReplacesEveryAttemptButPreservesTeacherLayerAndIsIdempotent() {
        val sourceRoot = Files.createTempDirectory("masternote-student-checkpoint-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-student-checkpoint-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val attemptOne = sourceDocument.addStroke(stroke("student-source").copy(attemptNo = 1))
            val attemptTwo = sourceDocument.addStroke(
                stroke("student-source").copy(
                    attemptNo = 2,
                    points = listOf(PagePoint(20f, 30f), PagePoint(40f, 50f)),
                ),
            )
            source.append(attemptOne)
            source.append(attemptTwo)

            val firstEncoding = source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val stableEncoding = firstEncoding.copyOf()
            firstEncoding.fill(0)
            assertArrayEquals(stableEncoding, source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE))

            val localBookId = "local-copy-of-book"
            val target = PageOperationLogStore(targetRoot, checkpointInterval = 2)
            val targetDocument = AnnotationDocument(AnnotationSnapshot.empty(localBookId, PAGE))
            val oldStudent = targetDocument.addStroke(stroke("old-student"))
            val teacher = targetDocument.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 123L,
                ),
            )
            target.append(oldStudent)
            target.append(teacher)
            val logBytesBeforeRemoteCheckpoint = target.operationLogFile(localBookId, PAGE).length()

            val applied = target.applyStudentLayerCheckpoint(localBookId, PAGE, stableEncoding)
            val expectedStudentIds = setOf(
                attemptOne.addedAssets.single().id,
                attemptTwo.addedAssets.single().id,
            )
            val activeStudents = applied.snapshot.activeStrokes.filter { it.authorId == "student" }
            assertTrue(applied.changed)
            assertEquals(expectedStudentIds, activeStudents.mapTo(hashSetOf(), StrokeAsset::id))
            assertEquals(setOf(1, 2), activeStudents.mapTo(hashSetOf(), StrokeAsset::attemptNo))
            assertTrue(oldStudent.addedAssets.single().id !in applied.snapshot.activeStrokeIds)
            assertTrue(teacher.addedAssets.single().id in applied.snapshot.activeStrokeIds)
            assertEquals(teacher.addedAssets.single(), applied.snapshot.assets[teacher.addedAssets.single().id])
            assertEquals(
                "Remote full-layer payload must not be duplicated into operations.log",
                logBytesBeforeRemoteCheckpoint,
                target.operationLogFile(localBookId, PAGE).length(),
            )
            assertTrue(targetRoot.resolve("$localBookId/pages/$PAGE/checkpoint.json").isFile)

            val duplicate = target.applyStudentLayerCheckpoint(localBookId, PAGE, stableEncoding)
            assertFalse(duplicate.changed)
            assertEquals(applied.checkpointId, duplicate.checkpointId)
            assertEquals(applied.snapshot.revision, duplicate.snapshot.revision)

            val reloaded = PageOperationLogStore(targetRoot).loadPage(localBookId, PAGE)
            assertEquals(applied.snapshot.revision, reloaded.revision)
            assertEquals(applied.snapshot.activeStrokeIds, reloaded.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun emptyStudentCheckpointRemovesStudentLayerWithoutTouchingTeacherInk() {
        val sourceRoot = Files.createTempDirectory("masternote-empty-student-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-empty-student-target").toFile()
        try {
            val checkpoint = PageOperationLogStore(sourceRoot)
                .encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val student = document.addStroke(stroke("student-device"))
            val teacher = document.addStroke(
                stroke("teacher-device").copy(authorId = "teacher", publishedAtEpochMillis = 50L),
            )
            target.append(student)
            target.append(teacher)

            val applied = target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, checkpoint)

            assertTrue(applied.changed)
            assertEquals(listOf(teacher.addedAssets.single()), applied.snapshot.activeStrokes)
            assertTrue(student.addedAssets.single().id in applied.snapshot.assets)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun checkpointCarriesRedoPayloadAndInactiveParentChain() {
        val sourceRoot = Files.createTempDirectory("masternote-complete-student-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-complete-student-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val parent = document.addStroke(
                stroke("student-device").copy(
                    points = listOf(PagePoint(5f, 5f), PagePoint(8f, 8f)),
                ),
            )
            source.append(parent)
            val parentAsset = parent.addedAssets.single()
            val child = document.addStroke(
                stroke("student-device").copy(
                    points = listOf(PagePoint(200f, 200f), PagePoint(220f, 220f)),
                    parentStrokeId = parentAsset.id,
                ),
            )
            source.append(child)
            source.append(
                requireNotNull(
                    document.erase(
                        page = PAGE,
                        path = parentAsset.points,
                        radius = 4f,
                        wholeStroke = true,
                        authorId = "student",
                        attemptNo = 1,
                        deviceId = "student-device",
                    ),
                ),
            )
            val redoAssetChange = document.addStroke(
                stroke("student-device").copy(
                    points = listOf(PagePoint(400f, 400f), PagePoint(420f, 420f)),
                ),
            )
            source.append(redoAssetChange)
            source.append(requireNotNull(document.undo("student-device")))
            val redo = requireNotNull(document.redo("student-device"))
            assertTrue(redo.addedAssets.isEmpty())
            source.append(redo)

            val checkpoint = source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)
            val applied = target.applyStudentLayerCheckpoint("portable-local-book", PAGE, checkpoint)
            val childAsset = child.addedAssets.single()
            val redoAsset = redoAssetChange.addedAssets.single()

            assertEquals(setOf(childAsset.id, redoAsset.id), applied.snapshot.activeStrokeIds)
            assertEquals(parentAsset, applied.snapshot.assets[parentAsset.id])
            assertEquals(parentAsset.id, applied.snapshot.assets[childAsset.id]?.parentStrokeId)
            assertEquals(redoAsset, applied.snapshot.assets[redoAsset.id])
            assertTrue(parentAsset.id !in applied.snapshot.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun malformedWrongPageAndOversizedCheckpointsAreRejectedWithoutACommit() {
        val sourceRoot = Files.createTempDirectory("masternote-invalid-checkpoint-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-invalid-checkpoint-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            source.append(document.addStroke(stroke("student-device")))
            val valid = source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)

            val malformed = valid.copyOf().also { bytes -> bytes[bytes.lastIndex / 2] = 0 }
            assertThrows(Exception::class.java) {
                target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, malformed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(BOOK_ID, PAGE + 1, valid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(
                    BOOK_ID,
                    PAGE,
                    ByteArray(PageOperationLogStore.MAX_STUDENT_LAYER_CHECKPOINT_BYTES + 1),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(
                    localBookId = BOOK_ID,
                    pageNumber = PAGE,
                    checkpointBytes = valid,
                    expectedResultLayerSha256 = "0".repeat(64),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(
                    localBookId = BOOK_ID,
                    pageNumber = PAGE,
                    checkpointBytes = valid,
                    allowedAttemptNos = listOf(2),
                )
            }
            assertEquals(0L, target.loadPage(BOOK_ID, PAGE).revision)
            assertEquals(0L, target.loadPage(BOOK_ID, PAGE + 1).revision)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun portableLayerDigestsAndAtomicExportsExcludeUnrelatedLayersAndDefendBytes() {
        val sourceRoot = Files.createTempDirectory("masternote-layer-digest-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-layer-digest-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val student = document.addStroke(stroke("student-device"))
            source.append(student)
            val studentDigest = source.studentLayerSha256(BOOK_ID, PAGE)
            val studentExport = source.exportStudentLayerCheckpoint(
                BOOK_ID,
                PAGE,
                originDeviceId = "student-device",
            )

            assertTrue(Regex("[0-9a-f]{64}").matches(studentDigest))
            assertEquals(studentDigest, studentExport.layerSha256)
            assertEquals(1L, studentExport.originDeviceHighWater)
            val ownedStudentBytes = studentExport.checkpointBytes
            ownedStudentBytes.fill(0)
            assertFalse(studentExport.checkpointBytes.contentEquals(ownedStudentBytes))

            val publishedAttemptOne = document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    publishedAtEpochMillis = 100L,
                ),
            )
            source.append(publishedAttemptOne)
            val teacherDigest = source.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1)
            val teacherExport = source.exportPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1)
            assertTrue(Regex("[0-9a-f]{64}").matches(teacherDigest))
            assertEquals(teacherDigest, teacherExport.layerSha256)

            source.append(document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(40f, 40f), PagePoint(50f, 50f)),
                ),
            ))
            source.append(document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 200L,
                    points = listOf(PagePoint(60f, 60f), PagePoint(70f, 70f)),
                ),
            ))

            assertEquals(studentDigest, source.studentLayerSha256(BOOK_ID, PAGE))
            assertEquals(teacherDigest, source.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))

            val target = PageOperationLogStore(targetRoot)
            val studentApplied = target.applyStudentLayerCheckpoint(
                "different-local-book-id",
                PAGE,
                studentExport.copyCheckpointBytes(),
            )
            assertEquals(studentDigest, studentApplied.layerSha256)
            assertEquals(
                studentDigest,
                target.studentLayerSha256("different-local-book-id", PAGE),
            )
            val teacherApplied = target.applyPublishedTeacherLayerCheckpoint(
                "different-local-book-id",
                PAGE,
                1,
                teacherExport.copyCheckpointBytes(),
            )
            assertEquals(teacherDigest, teacherApplied.layerSha256)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun studentAndTeacherCheckpointsCanReapplyAAfterBAndSurviveReload() {
        val studentARoot = Files.createTempDirectory("masternote-checkpoint-student-a").toFile()
        val studentBRoot = Files.createTempDirectory("masternote-checkpoint-student-b").toFile()
        val teacherARoot = Files.createTempDirectory("masternote-checkpoint-teacher-a").toFile()
        val teacherBRoot = Files.createTempDirectory("masternote-checkpoint-teacher-b").toFile()
        val targetRoot = Files.createTempDirectory("masternote-checkpoint-a-b-a-target").toFile()
        try {
            val studentA = PageOperationLogStore(studentARoot).also { store ->
                store.append(
                    AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                        .addStroke(stroke("student-a")),
                )
            }
            val studentB = PageOperationLogStore(studentBRoot).also { store ->
                store.append(
                    AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                        .addStroke(stroke("student-b").copy(
                            points = listOf(PagePoint(100f, 100f), PagePoint(120f, 120f)),
                        )),
                )
            }
            val studentACheckpoint = studentA.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val studentBCheckpoint = studentB.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)
            val firstA = target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, studentACheckpoint)
            val b = target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, studentBCheckpoint)
            val secondA = target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, studentACheckpoint)

            assertTrue(firstA.changed)
            assertTrue(b.changed)
            assertTrue(secondA.changed)
            assertEquals(firstA.layerSha256, secondA.layerSha256)
            assertEquals(3L, secondA.snapshot.revision)

            fun teacherCheckpoint(root: java.io.File, device: String, offset: Float): ByteArray {
                val store = PageOperationLogStore(root)
                val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                store.append(document.addStroke(
                    stroke(device).copy(
                        authorId = "teacher",
                        attemptNo = 1,
                        publishedAtEpochMillis = 300L,
                        points = listOf(PagePoint(offset, offset), PagePoint(offset + 5f, offset + 5f)),
                    ),
                ))
                return store.encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1)
            }
            val teacherACheckpoint = teacherCheckpoint(teacherARoot, "teacher-a", 200f)
            val teacherBCheckpoint = teacherCheckpoint(teacherBRoot, "teacher-b", 300f)
            val teacherFirstA = target.applyPublishedTeacherLayerCheckpoint(
                BOOK_ID,
                PAGE,
                1,
                teacherACheckpoint,
            )
            target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1, teacherBCheckpoint)
            val teacherSecondA = target.applyPublishedTeacherLayerCheckpoint(
                BOOK_ID,
                PAGE,
                1,
                teacherACheckpoint,
            )
            assertTrue(teacherSecondA.changed)
            assertEquals(teacherFirstA.layerSha256, teacherSecondA.layerSha256)

            val reloaded = PageOperationLogStore(targetRoot)
            assertEquals(secondA.layerSha256, reloaded.studentLayerSha256(BOOK_ID, PAGE))
            assertEquals(
                teacherSecondA.layerSha256,
                reloaded.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1),
            )
        } finally {
            studentARoot.deleteRecursively()
            studentBRoot.deleteRecursively()
            teacherARoot.deleteRecursively()
            teacherBRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun studentCheckpointRejectsSameIdWithDifferentPayloadWithoutAppending() {
        val sourceRoot = Files.createTempDirectory("masternote-checkpoint-collision-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-checkpoint-collision-target").toFile()
        try {
            val sharedId = StrokeId("shared-student-stroke")
            val source = PageOperationLogStore(sourceRoot)
            source.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("source").copy(id = sharedId, width = 3f)),
            )
            val checkpoint = source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)
            target.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("target").copy(id = sharedId, width = 9f)),
            )
            val before = target.loadPage(BOOK_ID, PAGE)
            val beforeBytes = target.operationLogFile(BOOK_ID, PAGE).length()

            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, checkpoint)
            }

            assertEquals(before, target.loadPage(BOOK_ID, PAGE))
            assertEquals(beforeBytes, target.operationLogFile(BOOK_ID, PAGE).length())
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun pageOperationSyncStatsReportsPendingBytesAndOriginHighWater() {
        val root = Files.createTempDirectory("masternote-operation-log-stats").toFile()
        try {
            val store = PageOperationLogStore(root)
            assertEquals(
                PageOperationSyncStats(0L, 0L, 0, 0L),
                store.pageOperationSyncStats(BOOK_ID, PAGE, "student-device"),
            )
            store.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("student-device")),
            )

            val stats = store.pageOperationSyncStats(BOOK_ID, PAGE, "student-device")
            assertTrue(stats.logByteCount > 0L)
            assertTrue(stats.pendingEncodedByteCount > 0L)
            assertEquals(1, stats.pendingOperationCount)
            assertEquals(1L, stats.originDeviceHighWater)
            assertTrue(stats.lastMutationEpochMillis > 0L)
            assertEquals(
                PageOperationSyncStats(
                    stats.logByteCount,
                    0L,
                    0,
                    1L,
                    stats.lastMutationEpochMillis,
                ),
                store.pageOperationSyncStats(BOOK_ID, PAGE, "student-device", afterLogicalClock = 1L),
            )
            assertEquals(0L, store.pageOperationSyncStats(BOOK_ID, PAGE, "other-device").originDeviceHighWater)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publishedTeacherCheckpointReplacesOnlyExactAttemptAndPreservesDraftsStudentAndOtherAttempts() {
        val sourceRoot = Files.createTempDirectory("masternote-teacher-checkpoint-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-teacher-checkpoint-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val sourceDraft = sourceDocument.addStroke(
                stroke("source-teacher").copy(authorId = "teacher", attemptNo = 1),
            )
            source.append(sourceDraft)
            val sourcePublished = requireNotNull(sourceDocument.publishTeacherDrafts(1, "source-teacher"))
            source.append(sourcePublished)
            val excludedDraft = sourceDocument.addStroke(
                stroke("source-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(80f, 80f), PagePoint(90f, 90f)),
                ),
            )
            source.append(excludedDraft)
            val encoded = source.encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, attemptNo = 1)
            val stableEncoded = encoded.copyOf()
            source.append(sourceDocument.addStroke(
                stroke("unrelated-student").copy(
                    points = listOf(PagePoint(140f, 140f), PagePoint(150f, 150f)),
                ),
            ))
            source.append(sourceDocument.addStroke(
                stroke("unrelated-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 30L,
                    points = listOf(PagePoint(160f, 160f), PagePoint(170f, 170f)),
                ),
            ))
            encoded.fill(0)
            assertArrayEquals(
                stableEncoded,
                source.encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, attemptNo = 1),
            )

            val localBookId = "portable-teacher-book"
            val target = PageOperationLogStore(targetRoot, checkpointInterval = 2)
            val targetDocument = AnnotationDocument(AnnotationSnapshot.empty(localBookId, PAGE))
            val student = targetDocument.addStroke(stroke("student-device"))
            val oldPublished = targetDocument.addStroke(
                stroke("old-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    publishedAtEpochMillis = 10L,
                ),
            )
            val protectedDraft = targetDocument.addStroke(
                stroke("draft-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(120f, 120f), PagePoint(130f, 130f)),
                ),
            )
            val otherAttempt = targetDocument.addStroke(
                stroke("other-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 20L,
                ),
            )
            target.append(student)
            target.append(oldPublished)
            target.append(protectedDraft)
            target.append(otherAttempt)

            val applied = target.applyPublishedTeacherLayerCheckpoint(
                localBookId = localBookId,
                pageNumber = PAGE,
                attemptNo = 1,
                checkpointBytes = stableEncoded,
            )
            val publishedAsset = sourcePublished.addedAssets.single()
            val sourceParent = sourceDraft.addedAssets.single()

            assertTrue(applied.changed)
            assertEquals(
                setOf(
                    student.addedAssets.single().id,
                    protectedDraft.addedAssets.single().id,
                    otherAttempt.addedAssets.single().id,
                    publishedAsset.id,
                ),
                applied.snapshot.activeStrokeIds,
            )
            assertTrue(oldPublished.addedAssets.single().id !in applied.snapshot.activeStrokeIds)
            assertTrue(excludedDraft.addedAssets.single().id !in applied.snapshot.assets)
            assertEquals(sourceParent, applied.snapshot.assets[sourceParent.id])
            assertTrue(sourceParent.id !in applied.snapshot.activeStrokeIds)
            assertEquals(sourceParent.id, applied.snapshot.assets[publishedAsset.id]?.parentStrokeId)

            val duplicate = target.applyPublishedTeacherLayerCheckpoint(
                localBookId,
                PAGE,
                1,
                stableEncoded,
            )
            assertFalse(duplicate.changed)
            assertEquals(applied.checkpointId, duplicate.checkpointId)
            assertEquals(applied.snapshot.revision, duplicate.snapshot.revision)

            val reloaded = PageOperationLogStore(targetRoot).loadPage(localBookId, PAGE)
            assertEquals(applied.snapshot.activeStrokeIds, reloaded.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun emptyPublishedTeacherCheckpointRemovesOnlyExactPublishedLayer() {
        val sourceRoot = Files.createTempDirectory("masternote-empty-teacher-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-empty-teacher-target").toFile()
        try {
            val checkpoint = PageOperationLogStore(sourceRoot)
                .encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, attemptNo = 1)
            val target = PageOperationLogStore(targetRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val student = document.addStroke(stroke("student-device"))
            val published = document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    publishedAtEpochMillis = 100L,
                ),
            )
            val draft = document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(50f, 50f), PagePoint(60f, 60f)),
                ),
            )
            val otherAttempt = document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 200L,
                ),
            )
            target.append(student)
            target.append(published)
            target.append(draft)
            target.append(otherAttempt)

            val applied = target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1, checkpoint)

            assertTrue(applied.changed)
            assertEquals(
                setOf(
                    student.addedAssets.single().id,
                    draft.addedAssets.single().id,
                    otherAttempt.addedAssets.single().id,
                ),
                applied.snapshot.activeStrokeIds,
            )
            assertTrue(published.addedAssets.single().id in applied.snapshot.assets)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun publishedTeacherCheckpointRejectsMalformedWrongTargetAndOversizeWithoutCommit() {
        val sourceRoot = Files.createTempDirectory("masternote-invalid-teacher-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-invalid-teacher-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            source.append(document.addStroke(stroke("teacher").copy(
                authorId = "teacher",
                attemptNo = 1,
                publishedAtEpochMillis = 100L,
            )))
            val valid = source.encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1)
            val target = PageOperationLogStore(targetRoot)

            val malformed = valid.copyOf().also { bytes -> bytes[bytes.lastIndex / 2] = 0 }
            assertThrows(Exception::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1, malformed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE + 1, 1, valid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 2, valid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(
                    BOOK_ID,
                    PAGE,
                    1,
                    ByteArray(PageOperationLogStore.MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES + 1),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(
                    localBookId = BOOK_ID,
                    pageNumber = PAGE,
                    attemptNo = 1,
                    checkpointBytes = valid,
                    expectedResultLayerSha256 = "0".repeat(64),
                )
            }
            assertEquals(0L, target.loadPage(BOOK_ID, PAGE).revision)
            assertEquals(0L, target.loadPage(BOOK_ID, PAGE + 1).revision)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun studentOperationAppendAcceptsStudentDeltaAndRejectsTeacherAddOrRemoval() {
        val sourceRoot = Files.createTempDirectory("masternote-student-delta-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-student-delta-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceStudentDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val studentAdded = sourceStudentDocument.addStroke(stroke("student-device"))
            source.append(studentAdded)
            val encodedStudentAdd = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "student-device",
                0L,
            ).single()

            val target = PageOperationLogStore(targetRoot)
            assertEquals(1L, target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedStudentAdd))
            assertEquals(1L, target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedStudentAdd))
            assertTrue(studentAdded.addedAssets.single().id in target.loadPage(BOOK_ID, PAGE).activeStrokeIds)

            val sourceTeacherDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val teacherAdded = sourceTeacherDocument.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    publishedAtEpochMillis = 10L,
                ),
            )
            source.append(teacherAdded)
            val encodedTeacherAdd = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "teacher-device",
                0L,
            ).single()
            assertThrows(IllegalArgumentException::class.java) {
                target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedTeacherAdd)
            }

            target.appendEncodedOperation(BOOK_ID, PAGE, encodedTeacherAdd)
            val teacherAsset = teacherAdded.addedAssets.single()
            val teacherErase = requireNotNull(
                sourceTeacherDocument.erase(
                    page = PAGE,
                    path = teacherAsset.points,
                    radius = 20f,
                    wholeStroke = true,
                    authorId = "teacher",
                    attemptNo = 1,
                    deviceId = "teacher-device",
                ),
            )
            source.append(teacherErase)
            val encodedTeacherErase = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "teacher-device",
                1L,
            ).single()
            assertThrows(IllegalArgumentException::class.java) {
                target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedTeacherErase)
            }
            assertTrue(teacherAsset.id in target.loadPage(BOOK_ID, PAGE).activeStrokeIds)

            val studentAsset = studentAdded.addedAssets.single()
            val studentErase = requireNotNull(
                sourceStudentDocument.erase(
                    page = PAGE,
                    path = studentAsset.points,
                    radius = 20f,
                    wholeStroke = true,
                    authorId = "student",
                    attemptNo = 1,
                    deviceId = "student-device",
                ),
            )
            source.append(studentErase)
            val encodedStudentErase = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "student-device",
                1L,
            ).single()
            target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedStudentErase)
            val finalSnapshot = target.loadPage(BOOK_ID, PAGE)
            assertTrue(studentAsset.id !in finalSnapshot.activeStrokeIds)
            assertTrue(teacherAsset.id in finalSnapshot.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun studentOperationExportSkipsTeacherHistoryCreatedByTheSamePhysicalDevice() {
        val sourceRoot = Files.createTempDirectory("masternote-student-export-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-student-export-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val firstStudent = document.addStroke(stroke("shared-device").copy(attemptNo = 1))
            source.append(firstStudent)
            source.append(
                document.addStroke(
                    stroke("shared-device").copy(
                        authorId = "teacher",
                        attemptNo = 1,
                        publishedAtEpochMillis = null,
                    ),
                ),
            )
            val secondStudent = document.addStroke(
                stroke("shared-device").copy(
                    attemptNo = 1,
                    points = listOf(PagePoint(40f, 40f), PagePoint(50f, 50f)),
                ),
            )
            source.append(secondStudent)

            val outgoing = source.encodedStudentOperationsAfter(
                BOOK_ID,
                PAGE,
                "shared-device",
                0L,
            )
            assertEquals(2, outgoing.size)

            val target = PageOperationLogStore(targetRoot)
            outgoing.forEach { target.appendEncodedStudentOperation(BOOK_ID, PAGE, it) }
            assertEquals(
                setOf(firstStudent.addedAssets.single().id, secondStudent.addedAssets.single().id),
                target.loadPage(BOOK_ID, PAGE).activeStrokeIds,
            )
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun atomicStudentDeltaAppliesOneRecordPreservesTeacherAndReplayIsNoOp() {
        val sourceRoot = Files.createTempDirectory("masternote-atomic-delta-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-atomic-delta-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val first = sourceDocument.addStroke(stroke("student-origin").copy(attemptNo = 1))
            source.append(first)
            val second = sourceDocument.addStroke(
                stroke("student-origin").copy(
                    attemptNo = 2,
                    points = listOf(PagePoint(20f, 20f), PagePoint(30f, 30f)),
                ),
            )
            source.append(second)
            source.append(requireNotNull(sourceDocument.erase(
                page = PAGE,
                path = first.addedAssets.single().points,
                radius = 20f,
                wholeStroke = true,
                authorId = "student",
                attemptNo = 1,
                deviceId = "student-origin",
            )))
            val operations = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                originDeviceId = "student-origin",
                logicalClock = 0L,
            )
            assertEquals(listOf(1L, 2L, 3L), operations.map { source.operationCursor(it).logicalClock })
            val expectedDigest = source.studentLayerSha256(BOOK_ID, PAGE)

            val target = PageOperationLogStore(targetRoot)
            val targetDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val teacher = targetDocument.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 500L,
                ),
            )
            target.append(teacher)
            val revisionBefore = target.loadPage(BOOK_ID, PAGE).revision

            val applied = target.applyEncodedStudentDelta(
                localBookId = BOOK_ID,
                pageNumber = PAGE,
                encodedOperations = operations,
                expectedOriginDeviceId = "student-origin",
                baseOriginCursor = 0L,
                sourceOriginCursor = 3L,
                allowedAttemptNos = listOf(1, 2),
                expectedResultLayerSha256 = expectedDigest,
            )

            assertTrue(applied.changed)
            assertEquals(revisionBefore + 1L, applied.snapshot.revision)
            assertEquals(expectedDigest, applied.layerSha256)
            assertEquals(3L, applied.sourceOriginCursor)
            assertEquals(setOf(second.addedAssets.single().id), applied.snapshot.activeStrokes
                .filter { it.authorId == "student" }
                .mapTo(hashSetOf(), StrokeAsset::id))
            assertTrue(teacher.addedAssets.single().id in applied.snapshot.activeStrokeIds)
            assertEquals(3L, target.loadPageState(BOOK_ID, PAGE).operationClockHighWater)
            assertEquals(3L, target.maxOperationClock(BOOK_ID, PAGE, "student-layer-delta"))

            val replay = target.applyEncodedStudentDelta(
                localBookId = BOOK_ID,
                pageNumber = PAGE,
                encodedOperations = operations,
                expectedOriginDeviceId = "student-origin",
                baseOriginCursor = 0L,
                sourceOriginCursor = 3L,
                allowedAttemptNos = setOf(1, 2),
                expectedResultLayerSha256 = expectedDigest,
            )
            assertFalse(replay.changed)
            assertEquals(applied.snapshot.revision, replay.snapshot.revision)

            val reloaded = PageOperationLogStore(targetRoot)
            assertEquals(expectedDigest, reloaded.studentLayerSha256(BOOK_ID, PAGE))
            assertTrue(teacher.addedAssets.single().id in reloaded.loadPage(BOOK_ID, PAGE).activeStrokeIds)
            assertEquals(3L, reloaded.loadPageState(BOOK_ID, PAGE).operationClockHighWater)
            assertEquals(3L, reloaded.maxOperationClock(BOOK_ID, PAGE, "student-layer-delta"))
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun malformedIncompleteOrWrongDigestAtomicDeltaLeavesNoPrefixMutation() {
        val sourceRoot = Files.createTempDirectory("masternote-invalid-atomic-delta-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-invalid-atomic-delta-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            source.append(document.addStroke(stroke("student-origin")))
            source.append(document.addStroke(stroke("student-origin").copy(
                points = listOf(PagePoint(40f, 40f), PagePoint(50f, 50f)),
            )))
            val operations = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "student-origin",
                0L,
            )
            val expectedDigest = source.studentLayerSha256(BOOK_ID, PAGE)

            val target = PageOperationLogStore(targetRoot)
            target.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("teacher").copy(
                        authorId = "teacher",
                        publishedAtEpochMillis = 1L,
                    )),
            )
            val before = target.loadPage(BOOK_ID, PAGE)
            val beforeBytes = target.operationLogFile(BOOK_ID, PAGE).length()

            assertThrows(IllegalArgumentException::class.java) {
                target.applyEncodedStudentDelta(
                    BOOK_ID,
                    PAGE,
                    operations.dropLast(1),
                    "student-origin",
                    0L,
                    2L,
                    setOf(1),
                    expectedDigest,
                )
            }
            assertThrows(Exception::class.java) {
                target.applyEncodedStudentDelta(
                    BOOK_ID,
                    PAGE,
                    listOf(operations.first(), byteArrayOf('{'.code.toByte())),
                    "student-origin",
                    0L,
                    2L,
                    setOf(1),
                    expectedDigest,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyEncodedStudentDelta(
                    BOOK_ID,
                    PAGE,
                    operations,
                    "student-origin",
                    0L,
                    2L,
                    setOf(1),
                    "0".repeat(64),
                )
            }

            assertEquals(before, target.loadPage(BOOK_ID, PAGE))
            assertEquals(beforeBytes, target.operationLogFile(BOOK_ID, PAGE).length())
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun checkpointExportTupleStaysSelfConsistentWhileStudentWrites() {
        val sourceRoot = Files.createTempDirectory("masternote-export-race-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-export-race-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val target = PageOperationLogStore(targetRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val writerFailure = AtomicReference<Throwable?>()
            val writer = Thread {
                runCatching {
                    repeat(12) { index ->
                        source.append(document.addStroke(stroke("race-origin").copy(
                            points = listOf(
                                PagePoint(index.toFloat(), index.toFloat()),
                                PagePoint(index + 1f, index + 1f),
                            ),
                        )))
                        Thread.yield()
                    }
                }.onFailure(writerFailure::set)
            }
            writer.start()
            repeat(16) {
                val exported = source.exportStudentLayerCheckpoint(
                    BOOK_ID,
                    PAGE,
                    originDeviceId = "race-origin",
                )
                val applied = target.applyStudentLayerCheckpoint(
                    "portable-race-target",
                    PAGE,
                    exported.copyCheckpointBytes(),
                )
                assertEquals(exported.layerSha256, applied.layerSha256)
                assertEquals(
                    exported.layerSha256,
                    target.studentLayerSha256("portable-race-target", PAGE),
                )
                val activeHighWater = applied.snapshot.activeStrokes
                    .filter { it.authorId == "student" }
                    .maxOfOrNull(StrokeAsset::logicalClock) ?: 0L
                assertTrue(exported.originDeviceHighWater >= activeHighWater)
            }
            writer.join()
            writerFailure.get()?.let { throw AssertionError("Concurrent writer failed", it) }
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun teacherReviewPublishIntentJournalIsDurableExactAndCorruptionIsIsolated() {
        val root = Files.createTempDirectory("masternote-teacher-publish-intents").toFile()
        try {
            val store = PageOperationLogStore(root)
            store.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("student-device")),
            )
            val first = TeacherReviewPublishIntent(BOOK_ID, PAGE, 1, 100L)
            val otherAttempt = TeacherReviewPublishIntent(BOOK_ID, PAGE, 2, 90L)
            val firstPublication = store.recordTeacherReviewPublishIntent(first)
            store.recordTeacherReviewPublishIntent(otherAttempt)
            val repeatedPublication = store.recordTeacherReviewPublishIntent(first.copy(updatedAtEpochMillis = 120L))
            val latestPublication = store.recordTeacherReviewPublishIntent(first.copy(updatedAtEpochMillis = 110L))

            val persisted = PageOperationLogStore(root)
            val intents = persisted.teacherReviewPublishIntents()
            // Every explicit publish is a new delivery intent, even when its immutable bytes are
            // unchanged. Call order wins; wall-clock rollback cannot resurrect the older press.
            assertEquals(listOf(90L, 121L), intents.map { it.updatedAtEpochMillis })
            assertEquals(listOf(2, 1), intents.map { it.attemptNo })
            assertTrue(firstPublication.publicationId != repeatedPublication.publicationId)
            assertTrue(repeatedPublication.publicationId != latestPublication.publicationId)
            intents.forEach { intent ->
                assertTrue(intent.publicationId.isNotEmpty())
                assertTrue(
                    persisted.teacherReviewPublicationArtifact(
                        intent.bookId,
                        intent.pageNumber,
                        intent.attemptNo,
                        intent.publicationId,
                    )!!.copyCheckpointBytes().isNotEmpty(),
                )
            }
            val reloaded = PageOperationLogStore(root)
            assertTrue(reloaded.removeTeacherReviewPublishIntent(BOOK_ID, PAGE, 1))
            assertFalse(reloaded.removeTeacherReviewPublishIntent(BOOK_ID, PAGE, 1))
            assertEquals(listOf(2), PageOperationLogStore(root).teacherReviewPublishIntents().map { it.attemptNo })

            root.resolve("teacher-review-publish-intents.json").writeText("{broken", Charsets.UTF_8)
            val afterCorruption = PageOperationLogStore(root)
            assertTrue(afterCorruption.teacherReviewPublishIntents().isEmpty())
            assertEquals(1L, afterCorruption.loadPage(BOOK_ID, PAGE).revision)
            assertTrue(root.listFiles().orEmpty().any {
                it.name.startsWith("teacher-review-publish-intents.json.corrupt-")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sharedTeacherReviewGradeDigestTracksAttemptMarksNotCrossAttemptMetadata() {
        val root = Files.createTempDirectory("masternote-teacher-grade-digest").toFile()
        try {
            val store = PageOperationLogStore(root)
            val groups = listOf(
                MarkGroup(
                    id = "grade_group_b",
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    anchor = PagePoint(21.5f, 44.25f, 0.8f),
                    marks = listOf(
                        Mark(1, MarkColor.RED, 801L),
                        Mark(1, MarkColor.GRAY, 802L, hiddenAtEpochMillis = 803L),
                    ),
                    createdAtEpochMillis = 700L,
                    hiddenAtEpochMillis = 900L,
                    syncRevision = 5L,
                    lastModifiedByDeviceId = "teacher_device",
                ),
                MarkGroup(
                    id = "grade_group_a",
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    anchor = PagePoint(1f, 2f),
                    marks = listOf(Mark(1, MarkColor.BLUE, 600L)),
                    createdAtEpochMillis = 500L,
                ),
            )
            store.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(BOOK_ID, PAGE, 1, 1_000L),
                groups,
            )
            val baseline = teacherReviewMarkGroupsSha256(groups)
            assertEquals(baseline, store.teacherReviewMarkGroupsStateSha256(groups))
            assertEquals(
                baseline,
                teacherReviewMarkGroupsSha256(groups.map { group ->
                    group.copy(
                        anchor = PagePoint(900f, 800f),
                        hiddenAtEpochMillis = 1_500L,
                        syncRevision = group.syncRevision + 10L,
                        lastModifiedByDeviceId = "another-attempt-writer",
                    )
                }),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preparedTeacherReviewPromotesAfterRestartOnlyWhenPublishedLayerIsDurable() {
        val root = Files.createTempDirectory("masternote-teacher-publish-prepare").toFile()
        try {
            val store = PageOperationLogStore(root)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            store.append(
                document.addStroke(
                    stroke("teacher-device").copy(authorId = "teacher", attemptNo = 1),
                ),
            )
            val published = requireNotNull(document.publishTeacherDrafts(1, "teacher-device"))
            val prepared = store.prepareTeacherReviewPublication(
                intent = TeacherReviewPublishIntent(
                    BOOK_ID,
                    PAGE,
                    1,
                    500L,
                    remotePairId = "pair_telegram_1",
                    remoteWorkbookToken = "workbook_telegram_1",
                    remoteManifestGeneration = 7L,
                    remoteManifestSequence = 9L,
                ),
                publishedSnapshot = published.snapshot,
                publishedMarkGroups = emptyList(),
            )

            val beforeInkCommit = PageOperationLogStore(root)
            assertEquals(listOf(prepared.publicationId), beforeInkCommit
                .teacherReviewPublicationPreparations().map { it.publicationId })
            assertEquals(
                "pair_telegram_1",
                beforeInkCommit.teacherReviewPublicationPreparations().single().remotePairId,
            )
            assertTrue(beforeInkCommit.teacherReviewPublishIntents().isEmpty())
            assertEquals(
                null,
                beforeInkCommit.promotePreparedTeacherReviewPublication(
                    BOOK_ID,
                    PAGE,
                    1,
                    prepared.publicationId,
                    emptyList(),
                ),
            )

            store.append(published)
            val restarted = PageOperationLogStore(root)
            val promoted = requireNotNull(
                restarted.promotePreparedTeacherReviewPublication(
                    BOOK_ID,
                    PAGE,
                    1,
                    prepared.publicationId,
                    emptyList(),
                ),
            )
            assertEquals(prepared.publicationId, promoted.publicationId)
            assertEquals("pair_telegram_1", promoted.remotePairId)
            assertEquals("workbook_telegram_1", promoted.remoteWorkbookToken)
            assertEquals(7L, promoted.remoteManifestGeneration)
            assertEquals(9L, promoted.remoteManifestSequence)
            assertTrue(restarted.teacherReviewPublicationPreparations().isEmpty())
            assertEquals(
                prepared.publicationId,
                restarted.teacherReviewPublishIntents().single().publicationId,
            )
            val promotedAgain = requireNotNull(
                restarted.promotePreparedTeacherReviewPublication(
                    BOOK_ID,
                    PAGE,
                    1,
                    prepared.publicationId,
                    emptyList(),
                ),
            )
            assertEquals(promoted, promotedAgain)
            assertTrue(restarted.teacherReviewPublicationPreparations().isEmpty())
            assertEquals(
                listOf(prepared.publicationId),
                restarted.teacherReviewPublishIntents().map { it.publicationId },
            )
            assertTrue(
                restarted.teacherReviewPublicationArtifact(
                    BOOK_ID,
                    PAGE,
                    1,
                    prepared.publicationId,
                ) != null,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acknowledgedTeacherAuthorityRetainsExactArtifactAndNeverRebuildsFromLaterDraft() {
        val root = Files.createTempDirectory("masternote-teacher-authority").toFile()
        try {
            val store = PageOperationLogStore(root)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            store.append(document.addStroke(
                stroke("teacher-device").copy(authorId = "teacher", attemptNo = 1),
            ))
            store.append(requireNotNull(document.publishTeacherDrafts(1, "teacher-device")))
            val published = store.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    attemptNo = 1,
                    updatedAtEpochMillis = 1_000L,
                    remotePairId = "pair_student_one",
                ),
            )
            val frozenBytes = requireNotNull(store.teacherReviewPublicationArtifact(
                BOOK_ID,
                PAGE,
                1,
                published.publicationId,
            )).copyCheckpointBytes()

            assertTrue(store.removeTeacherReviewPublishIntent(
                BOOK_ID,
                PAGE,
                1,
                published.publicationId,
            ))
            assertTrue(store.teacherReviewPublishIntents().isEmpty())

            // This is a new local draft after the publish/ACK boundary and must never enter repair.
            val later = AnnotationDocument(
                initial = store.loadPageState(BOOK_ID, PAGE).snapshot,
                operationClockHighWater = store.loadPageState(BOOK_ID, PAGE).operationClockHighWater,
            )
            store.append(later.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(80f, 80f), PagePoint(90f, 90f)),
                ),
            ))

            val restarted = PageOperationLogStore(root)
            assertEquals(
                listOf(published.publicationId),
                restarted.teacherReviewAuthorityEvidence(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                ).map { it.publicationId },
            )
            // A retained legacy authority has no workbook provenance. It remains available to
            // legacy callers, but must never contaminate an exact-workbook metadata digest.
            assertTrue(restarted.teacherReviewAuthorityIntents(
                BOOK_ID,
                PAGE,
                "pair_student_one",
                "workbook_one",
            ).isEmpty())
            assertTrue(restarted.teacherReviewAuthorityEvidence(
                BOOK_ID,
                PAGE,
                "pair_student_one",
                "workbook_one",
                setOf(1),
            ).isEmpty())
            assertTrue(restarted.requeueTeacherReviewAuthorities(
                BOOK_ID,
                PAGE,
                "pair_student_one",
                setOf(1),
                "workbook_one",
            ).isEmpty())
            assertArrayEquals(
                frozenBytes,
                requireNotNull(restarted.teacherReviewPublicationArtifact(
                    BOOK_ID,
                    PAGE,
                    1,
                    published.publicationId,
                )).copyCheckpointBytes(),
            )
            assertEquals(
                listOf(published.publicationId),
                restarted.requeueTeacherReviewAuthorities(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    setOf(1),
                ).map { it.publicationId },
            )
            assertTrue(restarted.requeueTeacherReviewAuthorities(
                BOOK_ID,
                PAGE,
                "pair_student_one",
                setOf(2),
            ).isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                restarted.requeueTeacherReviewAuthorities(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    emptySet(),
                )
            }
            // Keep returning an already-present authority: after a crash the app tombstone may be
            // committed even though this outbox deletion was not, and it still needs exact repair.
            assertEquals(
                listOf(published.publicationId),
                restarted.requeueTeacherReviewAuthorities(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    setOf(1),
                ).map { it.publicationId },
            )
            // A restore invalidates both in-memory ledgers. An ACK through this older holder must
            // reload the authority before deciding whether the immutable artifact is unreferenced.
            restarted.resetCachedStateAfterRestore()
            assertTrue(restarted.removeTeacherReviewPublishIntent(
                BOOK_ID,
                PAGE,
                1,
                published.publicationId,
            ))
            assertArrayEquals(
                frozenBytes,
                requireNotNull(restarted.teacherReviewPublicationArtifact(
                    BOOK_ID,
                    PAGE,
                    1,
                    published.publicationId,
                )).copyCheckpointBytes(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingTeacherReviewStateMigratesLegacyOutboxBeforeManifestAndRetainsAuthorityAfterAck() {
        val root = Files.createTempDirectory("masternote-teacher-authority-missing-state").toFile()
        try {
            val original = PageOperationLogStore(root)
            val published = original.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    attemptNo = 1,
                    updatedAtEpochMillis = 1_000L,
                    remotePairId = "pair_student_one",
                    remoteWorkbookToken = "workbook_one",
                ),
                listOf(teacherReviewRecoveryMarkGroup()),
            )
            deleteTeacherReviewStateJournal(root)
            original.resetCachedStateAfterRestore()

            // Restore invalidates both ledgers. A manifest/authority read can then be the first
            // operation, before any ordinary outbox poll reloads the legacy migration source.
            val manifestFirst = original
            assertEquals(
                listOf(published.publicationId),
                manifestFirst.teacherReviewAuthorityEvidence(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    "workbook_one",
                ).map { it.publicationId },
            )
            assertTrue(manifestFirst.removeTeacherReviewPublishIntent(
                BOOK_ID,
                PAGE,
                1,
                published.publicationId,
            ))

            val afterAck = PageOperationLogStore(root)
            assertTrue(afterAck.teacherReviewPublishIntents().isEmpty())
            assertEquals(
                listOf(published.publicationId),
                afterAck.teacherReviewAuthorityIntents(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    "workbook_one",
                ).map { it.publicationId },
            )
            assertTrue(afterAck.teacherReviewPublicationArtifact(
                BOOK_ID,
                PAGE,
                1,
                published.publicationId,
            ) != null)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptTeacherReviewStateMigratesLegacyOutboxBeforeManifestAndRetainsAuthorityAfterAck() {
        val root = Files.createTempDirectory("masternote-teacher-authority-corrupt-state").toFile()
        try {
            val original = PageOperationLogStore(root)
            val published = original.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    attemptNo = 1,
                    updatedAtEpochMillis = 1_000L,
                    remotePairId = "pair_student_one",
                    remoteWorkbookToken = "workbook_one",
                ),
                listOf(teacherReviewRecoveryMarkGroup()),
            )
            deleteTeacherReviewStateJournal(root)
            root.resolve("teacher-review-state-v1.json").writeText("{broken", Charsets.UTF_8)
            original.resetCachedStateAfterRestore()

            val manifestFirst = original
            assertEquals(
                listOf(published.publicationId),
                manifestFirst.teacherReviewAuthorityEvidence(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    "workbook_one",
                ).map { it.publicationId },
            )
            assertTrue(root.listFiles().orEmpty().any {
                it.name.startsWith("teacher-review-state-v1.json.corrupt-")
            })
            assertTrue(manifestFirst.removeTeacherReviewPublishIntent(
                BOOK_ID,
                PAGE,
                1,
                published.publicationId,
            ))

            val afterAck = PageOperationLogStore(root)
            assertTrue(afterAck.teacherReviewPublishIntents().isEmpty())
            assertEquals(
                listOf(published.publicationId),
                afterAck.teacherReviewAuthorityIntents(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    "workbook_one",
                ).map { it.publicationId },
            )
            assertTrue(afterAck.teacherReviewPublicationArtifact(
                BOOK_ID,
                PAGE,
                1,
                published.publicationId,
            ) != null)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deferredAuthorityWorkbookBindingIsExactAndRepairsAuthorityFirstCrash() {
        val root = Files.createTempDirectory("masternote-teacher-authority-binding").toFile()
        try {
            val interrupted = PageOperationLogStore(
                root,
                beforeTeacherReviewWorkbookOutboxPersist = { error("simulated crash") },
            )
            val group = MarkGroup(
                id = "grade-binding",
                bookId = BOOK_ID,
                pageNumber = PAGE,
                anchor = PagePoint(10f, 20f),
                marks = listOf(Mark(1, MarkColor.BLUE, 2_000L)),
                createdAtEpochMillis = 1_900L,
                syncRevision = 1L,
                lastModifiedByDeviceId = "teacher-device",
            )
            val publication = interrupted.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(
                    BOOK_ID,
                    PAGE,
                    1,
                    2_100L,
                    remotePairId = "pair_student_one",
                    remoteManifestGeneration = 7L,
                    remoteManifestSequence = 9L,
                ),
                listOf(group),
            )
            assertEquals(null, interrupted.bindTeacherReviewAuthorityWorkbook(
                BOOK_ID,
                PAGE,
                1,
                "0".repeat(64),
                "pair_student_one",
                "workbook_one",
            ))
            assertEquals(null, interrupted.bindTeacherReviewAuthorityWorkbook(
                BOOK_ID,
                PAGE,
                1,
                publication.publicationId,
                "another_pair",
                "workbook_one",
            ))
            assertThrows(IllegalStateException::class.java) {
                interrupted.bindTeacherReviewAuthorityWorkbook(
                    BOOK_ID,
                    PAGE,
                    1,
                    publication.publicationId,
                    "pair_student_one",
                    "workbook_one",
                )
            }
            // The authority is already durable, while the simulated second-journal write did not run.
            assertEquals(
                "workbook_one",
                interrupted.teacherReviewAuthorityIntents(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    "workbook_one",
                ).single().remoteWorkbookToken,
            )
            assertEquals(null, interrupted.teacherReviewPublishIntents().single().remoteWorkbookToken)

            val restarted = PageOperationLogStore(root)
            assertEquals(
                "workbook_one",
                restarted.teacherReviewPublishIntents().single().remoteWorkbookToken,
            )
            assertEquals(
                "workbook_one",
                requireNotNull(restarted.bindTeacherReviewAuthorityWorkbook(
                    BOOK_ID,
                    PAGE,
                    1,
                    publication.publicationId,
                    "pair_student_one",
                    "workbook_one",
                )).remoteWorkbookToken,
            )
            assertEquals(null, restarted.bindTeacherReviewAuthorityWorkbook(
                BOOK_ID,
                PAGE,
                1,
                publication.publicationId,
                "pair_student_one",
                "another_workbook",
            ))

            val pairless = restarted.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(BOOK_ID, PAGE, 2, 3_100L),
                listOf(group.copy(marks = listOf(Mark(2, MarkColor.RED, 3_000L)))),
            )
            assertEquals(null, restarted.bindTeacherReviewAuthorityWorkbook(
                BOOK_ID,
                PAGE,
                2,
                pairless.publicationId,
                "pair_student_one",
                "workbook_one",
            ))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun teacherReviewPublicationOrderSurvivesWallClockRollbackPerExactTarget() {
        val root = Files.createTempDirectory("masternote-teacher-publication-order").toFile()
        try {
            val store = PageOperationLogStore(root)
            val group = MarkGroup(
                id = "grade-order",
                bookId = BOOK_ID,
                pageNumber = PAGE,
                anchor = PagePoint(10f, 20f),
                marks = listOf(Mark(1, MarkColor.BLUE, 2_000L)),
                createdAtEpochMillis = 1_900L,
                syncRevision = 1L,
                lastModifiedByDeviceId = "teacher-device",
            )
            val first = store.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(BOOK_ID, PAGE, 1, 5_000L),
                listOf(group),
            )
            val second = store.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(BOOK_ID, PAGE, 1, 100L),
                listOf(group.copy(anchor = PagePoint(30f, 40f), syncRevision = 2L)),
            )
            val independentAttempt = store.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(BOOK_ID, PAGE, 2, 100L),
                listOf(group.copy(
                    marks = listOf(Mark(2, MarkColor.RED, 3_000L)),
                    syncRevision = 3L,
                )),
            )

            assertEquals(5_000L, first.updatedAtEpochMillis)
            assertEquals(5_001L, second.updatedAtEpochMillis)
            assertEquals(100L, independentAttempt.updatedAtEpochMillis)
            assertEquals(
                5_001L,
                PageOperationLogStore(root).teacherReviewAuthorityIntents(BOOK_ID, PAGE)
                    .single { it.attemptNo == 1 }
                    .updatedAtEpochMillis,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun teacherReviewPublicationOrderUsesEveryCrashBoundaryWitness() {
        assertEquals(101L, nextTeacherReviewPublicationTimestamp(
            requestedEpochMillis = 100L,
            authorityEpochMillis = null,
            outboxEpochMillis = null,
            preparationEpochMillis = 100L,
        ))
        assertEquals(121L, nextTeacherReviewPublicationTimestamp(
            requestedEpochMillis = 90L,
            authorityEpochMillis = null,
            outboxEpochMillis = 120L,
            preparationEpochMillis = null,
        ))
        assertEquals(141L, nextTeacherReviewPublicationTimestamp(
            requestedEpochMillis = 80L,
            authorityEpochMillis = 140L,
            outboxEpochMillis = null,
            preparationEpochMillis = null,
        ))
        assertEquals(301L, nextTeacherReviewPublicationTimestamp(
            requestedEpochMillis = 300L,
            authorityEpochMillis = 200L,
            outboxEpochMillis = 250L,
            preparationEpochMillis = 300L,
        ))
        assertThrows(IllegalArgumentException::class.java) {
            nextTeacherReviewPublicationTimestamp(
                requestedEpochMillis = 0L,
                authorityEpochMillis = Long.MAX_VALUE,
                outboxEpochMillis = null,
                preparationEpochMillis = null,
            )
        }
    }

    @Test
    fun appliedTeacherReceiptPersistsPublicationOrderAndReplayCannotRewriteIt() {
        val root = Files.createTempDirectory("masternote-teacher-receipt-order").toFile()
        try {
            val store = PageOperationLogStore(root)
            val first = AppliedTeacherReviewReceipt(
                bookId = BOOK_ID,
                pageNumber = PAGE,
                attemptNo = 1,
                publicationId = "a".repeat(64),
                resultLayerSha256 = "b".repeat(64),
                markGroupsSha256 = "c".repeat(64),
                appliedAtEpochMillis = 8_000L,
                publishedAtEpochMillis = 7_000L,
                remotePairId = "pair_student_one",
                remoteWorkbookToken = "workbook_one",
            )
            store.recordAppliedTeacherReviewReceipt(first)
            store.recordAppliedTeacherReviewReceipt(first.copy(
                appliedAtEpochMillis = 9_000L,
                publishedAtEpochMillis = 6_000L,
            ))

            assertEquals(
                7_000L,
                PageOperationLogStore(root).appliedTeacherReviewReceipts(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    "workbook_one",
                ).single().publishedAtEpochMillis,
            )
            store.recordAppliedTeacherReviewReceipt(first.copy(
                publicationId = "d".repeat(64),
                publishedAtEpochMillis = 10_000L,
            ))
            assertEquals(
                10_000L,
                store.appliedTeacherReviewReceipts(BOOK_ID, PAGE).single().publishedAtEpochMillis,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exactReceiptLookupAndPublicationOrderingArePairScopedAndDeterministic() {
        val root = Files.createTempDirectory("masternote-teacher-receipt-disposition").toFile()
        try {
            val store = PageOperationLogStore(root)
            val current = AppliedTeacherReviewReceipt(
                bookId = BOOK_ID,
                pageNumber = PAGE,
                attemptNo = 3,
                publicationId = "a".repeat(64),
                resultLayerSha256 = "b".repeat(64),
                markGroupsSha256 = "c".repeat(64),
                appliedAtEpochMillis = 8_000L,
                publishedAtEpochMillis = 7_000L,
                remotePairId = "pair_student_one",
                remoteWorkbookToken = "workbook_one",
            )
            store.recordAppliedTeacherReviewReceipt(current)

            assertEquals(current, store.appliedTeacherReviewReceipt(
                BOOK_ID, PAGE, 3, "pair_student_one",
            ))
            assertEquals(current, store.appliedTeacherReviewReceipt(
                BOOK_ID, PAGE, 3, "pair_student_one", "workbook_one",
            ))
            assertEquals(null, store.appliedTeacherReviewReceipt(
                BOOK_ID, PAGE, 3, "another_pair",
            ))
            assertEquals(null, store.appliedTeacherReviewReceipt(
                BOOK_ID, PAGE, 3, "pair_student_one", "another_workbook",
            ))
            assertEquals(
                TeacherReviewPublicationOrderDisposition.STALE,
                teacherReviewPublicationOrderDisposition(current, "d".repeat(64), 0L),
            )
            assertEquals(
                TeacherReviewPublicationOrderDisposition.STALE,
                teacherReviewPublicationOrderDisposition(current, "d".repeat(64), 6_999L),
            )
            assertEquals(
                TeacherReviewPublicationOrderDisposition.DUPLICATE_VERIFY,
                teacherReviewPublicationOrderDisposition(current, current.publicationId, 7_000L),
            )
            assertEquals(
                TeacherReviewPublicationOrderDisposition.CONFLICT,
                teacherReviewPublicationOrderDisposition(current, "d".repeat(64), 7_000L),
            )
            assertEquals(
                TeacherReviewPublicationOrderDisposition.APPLY,
                teacherReviewPublicationOrderDisposition(current, "d".repeat(64), 7_001L),
            )
            assertEquals(
                TeacherReviewPublicationOrderDisposition.APPLY,
                teacherReviewPublicationOrderDisposition(
                    current.copy(publishedAtEpochMillis = 0L),
                    "d".repeat(64),
                    0L,
                ),
            )
            assertEquals(
                TeacherReviewPublicationOrderDisposition.APPLY,
                teacherReviewPublicationOrderDisposition(null, "d".repeat(64), 0L),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun teacherReviewTargetLockIsProcessGlobalAcrossStoreInstances() {
        val firstRoot = Files.createTempDirectory("masternote-teacher-lock-a").toFile()
        val secondRoot = Files.createTempDirectory("masternote-teacher-lock-b").toFile()
        val executor = Executors.newFixedThreadPool(2)
        val releaseFirst = CountDownLatch(1)
        try {
            val firstStore = PageOperationLogStore(firstRoot)
            val secondStore = PageOperationLogStore(secondRoot)
            val firstEntered = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val first = executor.submit {
                firstStore.withTeacherReviewTargetLock(BOOK_ID, PAGE, 4) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                secondStore.withTeacherReviewTargetLock(BOOK_ID, PAGE, 4) {
                    secondEntered.countDown()
                }
            }

            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(0L, secondEntered.count)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
        }
    }

    @Test
    fun appliedTeacherReceiptIsDurablePairScopedAndAdvertisedOnlyWhileActualStateMatches() {
        val teacherRoot = Files.createTempDirectory("masternote-teacher-receipt-source").toFile()
        val studentRoot = Files.createTempDirectory("masternote-teacher-receipt-target").toFile()
        try {
            val teacher = PageOperationLogStore(teacherRoot)
            val groups = listOf(
                MarkGroup(
                    id = "grade_exact_attempt",
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    anchor = PagePoint(10f, 20f),
                    marks = listOf(Mark(1, MarkColor.BLUE, 2_000L)),
                    createdAtEpochMillis = 1_900L,
                    syncRevision = 1L,
                    lastModifiedByDeviceId = "teacher-device",
                ),
            )
            val publication = teacher.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(BOOK_ID, PAGE, 1, 2_100L),
                groups,
            )
            val artifact = requireNotNull(teacher.teacherReviewPublicationArtifact(
                BOOK_ID,
                PAGE,
                1,
                publication.publicationId,
            ))
            val student = PageOperationLogStore(studentRoot)
            student.applyPublishedTeacherLayerCheckpoint(
                localBookId = BOOK_ID,
                pageNumber = PAGE,
                attemptNo = 1,
                checkpointBytes = artifact.copyCheckpointBytes(),
                expectedResultLayerSha256 = publication.resultLayerSha256,
            )
            student.recordAppliedTeacherReviewReceipt(
                AppliedTeacherReviewReceipt(
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    attemptNo = 1,
                    publicationId = publication.publicationId,
                    resultLayerSha256 = publication.resultLayerSha256,
                    markGroupsSha256 = teacherReviewMarkGroupsSha256(groups),
                    appliedAtEpochMillis = 2_200L,
                    remotePairId = "pair_student_one",
                    remoteWorkbookToken = "workbook_one",
                ),
            )
            student.recordAppliedTeacherReviewReceipt(
                AppliedTeacherReviewReceipt(
                    bookId = BOOK_ID,
                    pageNumber = PAGE,
                    attemptNo = 2,
                    publicationId = "a".repeat(64),
                    resultLayerSha256 = "b".repeat(64),
                    markGroupsSha256 = "c".repeat(64),
                    appliedAtEpochMillis = 2_201L,
                    remotePairId = "pair_student_one",
                    // Legacy journals do not have workbook provenance and must remain readable,
                    // but they are not eligible for an exact-workbook manifest claim.
                    remoteWorkbookToken = null,
                ),
            )

            val restarted = PageOperationLogStore(studentRoot)
            assertEquals(
                listOf(1, 2),
                restarted.appliedTeacherReviewReceipts(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                ).map { it.attemptNo },
            )
            assertEquals(
                listOf(1),
                restarted.appliedTeacherReviewReceipts(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    "workbook_one",
                ).map { it.attemptNo },
            )
            assertEquals(
                listOf(publication.publicationId),
                restarted.verifiedAppliedTeacherReviewEvidence(
                    BOOK_ID,
                    PAGE,
                    groups,
                    "pair_student_one",
                    "workbook_one",
                ).map { it.publicationId },
            )
            assertTrue(restarted.verifiedAppliedTeacherReviewEvidence(
                BOOK_ID,
                PAGE,
                groups,
                "another_pair",
                "workbook_one",
            ).isEmpty())
            assertTrue(restarted.verifiedAppliedTeacherReviewEvidence(
                BOOK_ID,
                PAGE,
                groups,
                "pair_student_one",
                "another_workbook",
            ).isEmpty())
            assertTrue(restarted.verifiedAppliedTeacherReviewEvidence(
                BOOK_ID,
                PAGE,
                emptyList(),
                "pair_student_one",
                "workbook_one",
            ).isEmpty())
        } finally {
            teacherRoot.deleteRecursively()
            studentRoot.deleteRecursively()
        }
    }

    @Test
    fun sharedGradeMetadataUsesLatestRetainedAuthorityAndDetectsStudentRollback() {
        val teacherRoot = Files.createTempDirectory("masternote-teacher-metadata-source").toFile()
        val studentRoot = Files.createTempDirectory("masternote-teacher-metadata-target").toFile()
        try {
            val teacher = PageOperationLogStore(teacherRoot)
            val firstGroup = MarkGroup(
                id = "shared-grade",
                bookId = BOOK_ID,
                pageNumber = PAGE,
                anchor = PagePoint(10f, 20f),
                marks = listOf(Mark(1, MarkColor.BLUE, 2_000L)),
                createdAtEpochMillis = 1_900L,
                syncRevision = 1L,
                lastModifiedByDeviceId = "teacher-a",
            )
            val secondGroup = firstGroup.copy(
                anchor = PagePoint(80f, 90f),
                marks = listOf(Mark(2, MarkColor.RED, 3_000L)),
                syncRevision = 2L,
                lastModifiedByDeviceId = "teacher-b",
            )
            val first = teacher.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(
                    BOOK_ID,
                    PAGE,
                    1,
                    2_100L,
                    remotePairId = "pair_student_one",
                    remoteWorkbookToken = "workbook_one",
                ),
                listOf(firstGroup),
            )
            val second = teacher.recordTeacherReviewPublishIntent(
                TeacherReviewPublishIntent(
                    BOOK_ID,
                    PAGE,
                    2,
                    3_100L,
                    remotePairId = "pair_student_one",
                    remoteWorkbookToken = "workbook_one",
                ),
                listOf(secondGroup),
            )
            listOf(first, second).forEach { publication ->
                assertTrue(teacher.removeTeacherReviewPublishIntent(
                    publication.bookId,
                    publication.pageNumber,
                    publication.attemptNo,
                    publication.publicationId,
                ))
            }
            val restartedTeacher = PageOperationLogStore(teacherRoot)
            val authority = restartedTeacher.teacherReviewAuthorityEvidence(
                BOOK_ID,
                PAGE,
                "pair_student_one",
                "workbook_one",
            )
            assertEquals(listOf(1, 2), authority.map { it.attemptNo })
            assertTrue(restartedTeacher.teacherReviewAuthorityEvidence(
                BOOK_ID,
                PAGE,
                "pair_student_one",
                "workbook_two",
                setOf(1, 2),
            ).isEmpty())

            val student = PageOperationLogStore(studentRoot)
            listOf(first to firstGroup, second to secondGroup).forEach { (publication, group) ->
                val artifact = requireNotNull(restartedTeacher.teacherReviewPublicationArtifact(
                    BOOK_ID,
                    PAGE,
                    publication.attemptNo,
                    publication.publicationId,
                ))
                student.applyPublishedTeacherLayerCheckpoint(
                    localBookId = BOOK_ID,
                    pageNumber = PAGE,
                    attemptNo = publication.attemptNo,
                    checkpointBytes = artifact.copyCheckpointBytes(),
                    expectedResultLayerSha256 = publication.resultLayerSha256,
                )
                student.recordAppliedTeacherReviewReceipt(
                    AppliedTeacherReviewReceipt(
                        bookId = BOOK_ID,
                        pageNumber = PAGE,
                        attemptNo = publication.attemptNo,
                        publicationId = publication.publicationId,
                        resultLayerSha256 = publication.resultLayerSha256,
                        markGroupsSha256 = teacherReviewMarkGroupsSha256(listOf(group)),
                        appliedAtEpochMillis = 4_000L + publication.attemptNo,
                        remotePairId = "pair_student_one",
                        remoteWorkbookToken = "workbook_one",
                    ),
                )
            }
            val currentStudentGroup = secondGroup.copy(
                marks = firstGroup.marks + secondGroup.marks,
            )
            val installed = student.verifiedAppliedTeacherReviewEvidence(
                BOOK_ID,
                PAGE,
                listOf(currentStudentGroup),
                "pair_student_one",
                "workbook_one",
            )
            assertEquals(authority, installed)
            assertTrue(student.verifiedAppliedTeacherReviewEvidence(
                BOOK_ID,
                PAGE,
                listOf(currentStudentGroup),
                "pair_student_one",
                "workbook_two",
                setOf(1, 2),
            ).isEmpty())
            val secondAuthority = restartedTeacher.teacherReviewAuthorityEvidence(
                BOOK_ID,
                PAGE,
                "pair_student_one",
                "workbook_one",
                setOf(2),
            )
            val secondInstalled = student.verifiedAppliedTeacherReviewEvidence(
                BOOK_ID,
                PAGE,
                listOf(currentStudentGroup),
                "pair_student_one",
                "workbook_one",
                setOf(2),
            )
            assertEquals(listOf(2), secondAuthority.map { it.attemptNo })
            assertEquals(secondAuthority, secondInstalled)
            assertThrows(IllegalArgumentException::class.java) {
                restartedTeacher.teacherReviewAuthorityEvidence(
                    BOOK_ID,
                    PAGE,
                    "pair_student_one",
                    "workbook_one",
                    emptySet(),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                student.verifiedAppliedTeacherReviewEvidence(
                    BOOK_ID,
                    PAGE,
                    listOf(currentStudentGroup),
                    "pair_student_one",
                    "workbook_one",
                    emptySet(),
                )
            }

            val anchorRollback = student.verifiedAppliedTeacherReviewEvidence(
                BOOK_ID,
                PAGE,
                listOf(currentStudentGroup.copy(anchor = firstGroup.anchor)),
                "pair_student_one",
                "workbook_one",
            )
            val visibilityRollback = student.verifiedAppliedTeacherReviewEvidence(
                BOOK_ID,
                PAGE,
                listOf(currentStudentGroup.copy(hiddenAtEpochMillis = 5_000L)),
                "pair_student_one",
                "workbook_one",
            )
            assertNotEquals(authority, anchorRollback)
            assertNotEquals(authority, visibilityRollback)
        } finally {
            teacherRoot.deleteRecursively()
            studentRoot.deleteRecursively()
        }
    }

    private fun stroke(deviceId: String) = StrokeAsset(
        pageNumber = PAGE,
        tool = StrokeTool.PEN,
        colorArgb = 0xFF17233C.toInt(),
        width = 3f,
        points = listOf(PagePoint(1f, 1f), PagePoint(2f, 2f)),
        authorId = "student",
        attemptNo = 1,
        deviceId = deviceId,
    )

    private fun teacherReviewRecoveryMarkGroup() = MarkGroup(
        id = "grade-recovery",
        bookId = BOOK_ID,
        pageNumber = PAGE,
        anchor = PagePoint(10f, 20f),
        marks = listOf(Mark(1, MarkColor.BLUE, 900L)),
        createdAtEpochMillis = 800L,
        syncRevision = 1L,
        lastModifiedByDeviceId = "teacher-device",
    )

    private fun deleteTeacherReviewStateJournal(root: java.io.File) {
        listOf(
            "teacher-review-state-v1.json",
            "teacher-review-state-v1.json.bak",
            "teacher-review-state-v1.json.tmp",
        ).forEach { name -> root.resolve(name).delete() }
    }

    private fun largePath(size: Int): List<PagePoint> = List(size) { index ->
        PagePoint((index % 512).toFloat(), (index / 512).toFloat())
    }

    private fun assertPointBitsEqual(expected: List<PagePoint>, actual: List<PagePoint>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) ->
            assertEquals(left.x.toRawBits(), right.x.toRawBits())
            assertEquals(left.y.toRawBits(), right.y.toRawBits())
            assertEquals(left.pressure.toRawBits(), right.pressure.toRawBits())
        }
    }

    private companion object {
        const val BOOK_ID = "book"
        const val PAGE = 3
    }
}
