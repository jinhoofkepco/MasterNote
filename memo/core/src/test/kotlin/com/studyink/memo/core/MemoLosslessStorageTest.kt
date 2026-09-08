package com.studyink.memo.core

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoLosslessStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `legacy v1 and v2 JSON resave as v3 with identical IDs revisions digest and float bits`() {
        for (version in 1..2) {
            val repo = StudentMemoRepository(temporary.newFolder("legacy-$version"), { 1_000L }, { MEMO_ID })
            val created = repo.create(TARGET, MemoAnchor(.2f, .3f))
            val coords = if (version == 1) listOf(MemoPoint(.12345679f, .8765432f, .31415927f))
                else listOf(MemoPoint(-2.345678f, 1.2345678f, .27182818f))
            val source = repo.replaceStrokes(TARGET, created.id, created.revision, listOf(stroke(1, coords)))
            val legacy = legacyMemo(source, version)
            val decoded = repo.decodeMemo(legacy)
            assertEquals(source, decoded)
            val encoded = repo.encodeMemo(decoded)
            assertEquals(3, JSONObject(encoded.toString(Charsets.UTF_8)).getInt("formatVersion"))
            val roundTrip = repo.decodeMemo(encoded)
            assertEquals(source.digestSha256, roundTrip.digestSha256)
            assertEquals(source.id, roundTrip.id)
            assertEquals(source.revision, roundTrip.revision)
            assertBits(coords, roundTrip.strokes.single().points)
            // Decode is read-only; the legacy disk file is upgraded only on a real save.
            val oldTarget = JSONObject(repo.exportSnapshot(TARGET).toString(Charsets.UTF_8))
                .put("formatVersion", version)
                .put("memos", JSONArray().put(JSONObject(legacy.toString(Charsets.UTF_8)).getJSONObject("memo")))
            val file = repo.targetFileForTest(TARGET)
            file.writeText(oldTarget.toString())
            val before = file.readBytes()
            assertEquals(source, repo.memo(TARGET, source.id))
            assertArrayEquals(before, file.readBytes())
            repo.move(TARGET, source.id, source.revision, MemoAnchor(.4f, .5f))
            assertEquals(3, JSONObject(file.readText()).getInt("formatVersion"))
        }
    }

    @Test fun `one hundred thousand binary points preserve every bit including pressure and negative zero`() {
        val special = listOf(MemoPoint(-0.0f, .0f, -0.0f), MemoPoint(-1024f, 1024f, Float.MIN_VALUE),
            MemoPoint(Float.fromBits(0x3eaaaaab), Float.MIN_VALUE, Float.MAX_VALUE))
        val points = List(100_000) { special[it % special.size] }
        val decoded = LosslessMemoPointCodec.decode(LosslessMemoPointCodec.encode(points), points.size)
        assertBits(points, decoded)
    }

    @Test fun `compressed receivers reject corrupt CRC trailing members expansion and fractional counts`() {
        val repo = StudentMemoRepository(temporary.newFolder("corrupt"), { 1_000L }, { MEMO_ID })
        val created = repo.create(TARGET, MemoAnchor(.2f, .3f))
        val memo = repo.replaceStrokes(TARGET, created.id, created.revision, listOf(stroke(1, listOf(MemoPoint(.1f, .2f, .3f)))))
        val original = repo.encodeMemo(memo)
        fun changed(block: (JSONObject) -> Unit): ByteArray = JSONObject(original.toString(Charsets.UTF_8)).also {
            block(it.getJSONObject("memo").getJSONArray("strokes").getJSONObject(0))
        }.toString().toByteArray()
        val gzip = Base64.getDecoder().decode(JSONObject(original.toString(Charsets.UTF_8))
            .getJSONObject("memo").getJSONArray("strokes").getJSONObject(0).getString("pointsData"))
        val crc = gzip.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }
        listOf(crc, gzip + byteArrayOf(0), gzip + gzip, gzip.copyOf(gzip.size - 1)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { repo.decodeMemo(changed { it.put("pointsData", Base64.getEncoder().encodeToString(invalid)) }) }
        }
        listOf(0, 100_001, 1.5, "1").forEach { count ->
            assertThrows(IllegalArgumentException::class.java) { repo.decodeMemo(changed { it.put("pointCount", count) }) }
        }
        val twoPoints = LosslessMemoPointCodec.encode(listOf(MemoPoint(.1f, .2f), MemoPoint(.3f, .4f)))
        assertThrows(IllegalArgumentException::class.java) { repo.decodeMemo(changed { it.put("pointsData", twoPoints) }) }
        val beforeInflation = LosslessMemoPointCodec.inflationCount.get()
        val overBudget = JSONObject(original.toString(Charsets.UTF_8))
        val template = overBudget.getJSONObject("memo").getJSONArray("strokes").getJSONObject(0)
        overBudget.getJSONObject("memo").put("strokes", JSONArray().apply {
            repeat(6) { put(JSONObject(template.toString()).put("pointCount", 100_000)) }
        })
        assertThrows(MemoCapacityExceededException::class.java) { repo.decodeMemo(overBudget.toString().toByteArray()) }
        assertEquals(beforeInflation, LosslessMemoPointCodec.inflationCount.get())
    }

    @Test fun `fifty thousand points exceed old JSON limit but save compressed and actual new point limit preserves disk`() {
        val repo = StudentMemoRepository(temporary.newFolder("large"), { 1_000L }, { MEMO_ID })
        val created = repo.create(TARGET, MemoAnchor(.2f, .3f))
        val points = List(50_000) { MemoPoint((it % 997) / 997f, (it % 991) / 991f, (it % 983) / 983f) }
        val saved = repo.replaceStrokes(TARGET, created.id, created.revision, listOf(stroke(1, points)))
        assertTrue(legacyMemo(saved, 1).size > 1_572_864)
        assertTrue(repo.usage(saved).encodedBytes < MemoStorageLimits.MAX_ENCODED_MEMO_BYTES)
        assertEquals(saved, repo.decodeMemo(repo.exportMemo(TARGET, saved.id)))
        val before = repo.targetFileForTest(TARGET).readBytes()
        val tooMany = List(6) { stroke(it + 1, List(100_000) { MemoPoint(.1f, .2f) }) }
        val error = assertThrows(MemoCapacityExceededException::class.java) {
            repo.replaceStrokes(TARGET, saved.id, saved.revision, tooMany)
        }
        assertEquals(MemoLimitScope.POINTS_PER_MEMO, error.scope)
        assertArrayEquals(before, repo.targetFileForTest(TARGET).readBytes())
        assertEquals(saved, repo.memo(TARGET, saved.id))
        assertEquals(8 * 1024 * 1024, MemoStorageLimits.MAX_ENCODED_MEMO_BYTES)
        assertEquals(10 * 1024 * 1024, MemoTransportLimits.MAX_ENCODED_MEMO_BYTES)
    }

    private fun legacyMemo(memo: StudentMemo, version: Int): ByteArray {
        val root = JSONObject(MemoJsonCodec.encodeMemo(memo).toString(Charsets.UTF_8)).put("formatVersion", version)
        val strokes = root.getJSONObject("memo").getJSONArray("strokes")
        memo.strokes.forEachIndexed { index, stroke ->
            strokes.getJSONObject(index).apply {
                remove("pointsEncoding"); remove("pointCount"); remove("pointsData")
                put("points", JSONArray().apply { stroke.points.forEach { p -> put(JSONObject()
                    .put("normalizedX", p.normalizedX.toDouble()).put("normalizedY", p.normalizedY.toDouble()).put("pressure", p.pressure.toDouble())) } })
            }
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun `actual compressed storage byte ceiling rejects dense short strokes without replacing the existing file`() {
        val repo = StudentMemoRepository(temporary.newFolder("byte-capacity"), { 1_000L }, { MEMO_ID })
        val created = repo.create(TARGET, MemoAnchor(.2f, .3f))
        val before = repo.targetFileForTest(TARGET).readBytes()
        val random = java.util.Random(921L)
        // Within all point/stroke count bounds, but per-stroke metadata plus lossless random
        // coordinates genuinely exceeds 8 MiB: test the byte check, not a proxy count check.
        val dense = List(MemoStorageLimits.MAX_STROKES_PER_MEMO) { index ->
            stroke(index + 1, List(24) { MemoPoint(random.nextFloat(), random.nextFloat(), random.nextFloat()) })
        }
        val error = assertThrows(MemoPayloadTooLargeException::class.java) {
            repo.replaceStrokes(TARGET, created.id, created.revision, dense)
        }
        assertEquals(MemoLimitScope.STORAGE_BYTES, error.scope)
        assertEquals(MemoStorageLimits.MAX_ENCODED_MEMO_BYTES, error.maximumBytes)
        assertTrue(error.actualBytes > error.maximumBytes)
        assertArrayEquals(before, repo.targetFileForTest(TARGET).readBytes())
        assertEquals(created, repo.memo(TARGET, created.id))
    }
    private fun assertBits(expected: List<MemoPoint>, actual: List<MemoPoint>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertEquals(a.normalizedX.toRawBits(), b.normalizedX.toRawBits())
            assertEquals(a.normalizedY.toRawBits(), b.normalizedY.toRawBits())
            assertEquals(a.pressure.toRawBits(), b.pressure.toRawBits())
        }
    }
    private fun stroke(id: Int, points: List<MemoPoint>) = MemoStroke(
        "10000000-0000-0000-0000-${id.toString().padStart(12, '0')}", MemoTool.PEN, 0xff102030.toInt(), .004f, points, 1_000L)
    private companion object {
        val TARGET = MemoTarget("book", 7, 1)
        const val MEMO_ID = "00000000-0000-0000-0000-000000000001"
    }
}
