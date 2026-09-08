package com.studyink.memo.core

import com.studyink.core.model.MasterNoteDataRootBus
import java.util.ConcurrentModificationException
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoHotCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `two foreground pages and all attempts reopen export and append without reinflating or recompressing old rebuilt strokes`() {
        val root = temporary.newFolder("hot")
        val repo = repository(root)
        val a = MemoTarget("book", 1, 1)
        val b = MemoTarget("book", 2, 1)
        val a2 = a.copy(attemptNo = 2)
        repo.focusPage(a.bookId, a.pageNumber)
        val original = ink(repo, a)
        repo.focusPage(b.bookId, b.pageNumber)
        ink(repo, b)
        ink(repo, a2) // Background work on another attempt does not create another page slot.
        val inflateBefore = inflations()
        val compressBefore = compressions()
        repeat(3) {
            assertSame(repo.snapshot(a), repo.snapshot(a))
            assertEquals(original, repo.memo(a, original.id))
            repo.snapshot(b)
            repo.snapshot(a2)
            assertEquals(3, repo.targets(null).size)
            val exported = repo.exportMemoWithMetadata(a, original.id)
            assertEquals(original, exported.memo)
            assertTrue(exported.bytes.isNotEmpty())
            repo.exportSnapshot(b)
            repo.usage(original)
        }
        assertEquals(inflateBefore, inflations())
        assertEquals(compressBefore, compressions())
        // Match the actual overlay: every old MemoStroke, List and MemoPoint is rebuilt.
        val rebuilt = original.strokes.map { s -> s.copy(points = s.points.map { p -> p.copy() }) }
        val saved = repo.replaceStrokes(a, original.id, original.revision, rebuilt + stroke(2))
        assertSame(original.strokes.single().points, saved.strokes.first().points)
        assertEquals(inflateBefore, inflations())
        assertEquals(compressBefore + 1, compressions())
        val beforeUsage = compressions()
        assertEquals(2, repo.usage(saved).strokeCount)
        repo.exportMemoWithMetadata(a, saved.id)
        repo.targets(null)
        assertEquals(beforeUsage, compressions())
        assertEquals(inflateBefore, inflations())
        val immutableBefore = saved.strokes.first().points.toList()
        val rejectedMutation = assertThrows(RuntimeException::class.java) {
            (saved.strokes.first().points as MutableList<MemoPoint>).clear()
        }
        assertTrue(rejectedMutation is ClassCastException || rejectedMutation is UnsupportedOperationException)
        assertEquals(immutableBefore, saved.strokes.first().points)
    }

    @Test fun `background reads never evict the latest two pages but a third foreground focus does`() {
        val repo = repository(temporary.newFolder("focus"))
        val a = MemoTarget("book", 1, 1)
        val b = a.copy(pageNumber = 2)
        val c = a.copy(pageNumber = 3)
        repo.focusPage(a.bookId, a.pageNumber); ink(repo, a)
        repo.focusPage(b.bookId, b.pageNumber); ink(repo, b)
        ink(repo, c)
        repeat(2) { repo.exportMemoWithMetadata(c, MEMO_ID) }
        val afterBackground = inflations()
        repo.snapshot(a); repo.snapshot(b)
        assertEquals(afterBackground, inflations())
        repo.focusPage(c.bookId, c.pageNumber)
        repo.snapshot(c)
        val afterFocus = inflations()
        repo.snapshot(b)
        assertEquals(afterFocus, inflations())
        repo.snapshot(a)
        assertTrue(inflations() > afterFocus)
        // Even that unfocused lookup must not displace the still-hot B/C pair.
        val afterCold = inflations()
        repo.snapshot(b); repo.snapshot(c)
        assertEquals(afterCold, inflations())
    }

    @Test fun `shared writers external file replacement restore and memory trimming cannot reuse stale decoded snapshots`() {
        val root = temporary.newFolder("invalidation")
        val repo = repository(root)
        val target = MemoTarget("book", 1, 1)
        repo.focusPage(target.bookId, target.pageNumber)
        val original = ink(repo, target)
        val oldBytes = repo.exportSnapshot(target)
        val another = repository(root)
        val moved = another.move(target, original.id, original.revision, MemoAnchor(.6f, .7f))
        val beforeRead = inflations()
        assertEquals(moved, repo.memo(target, original.id))
        assertEquals(beforeRead, inflations())
        val durable = repo.targetFileForTest(target).readBytes()
        assertThrows(ConcurrentModificationException::class.java) {
            repo.replaceStrokes(target, original.id, original.revision, listOf(stroke(2)))
        }
        assertArrayEquals(durable, repo.targetFileForTest(target).readBytes())
        val file = repo.targetFileForTest(target)
        file.writeBytes(oldBytes)
        assertTrue(file.setLastModified(System.currentTimeMillis() + 2_000))
        assertEquals(original, repo.memo(target, original.id))
        assertTrue(inflations() > beforeRead)
        val beforeRestore = inflations()
        MasterNoteDataRootBus.dataRootReplaced()
        assertEquals(original, repo.memo(target, original.id))
        assertTrue(inflations() > beforeRestore)
        val beforeTrim = inflations()
        StudentMemoRepository.trimMemoryCaches()
        assertEquals(original, repo.memo(target, original.id))
        assertTrue(inflations() > beforeTrim)
        val afterReload = inflations()
        repo.snapshot(target)
        assertEquals(afterReload, inflations())
        // Decoder seeds the encoded-body cache as well: this first save after a cold reload
        // compresses only the appended stroke, not the decoded old one.
        val beforeAppend = compressions()
        repo.replaceStrokes(target, original.id, original.revision,
            original.strokes.map { it.copy(points = it.points.map { point -> point.copy() }) } + stroke(2))
        assertEquals(beforeAppend + 1, compressions())
    }

    private fun repository(root: java.io.File): StudentMemoRepository {
        val clock = AtomicLong(1_000)
        return StudentMemoRepository(root, { clock.getAndIncrement() }, { MEMO_ID })
    }
    private fun ink(repo: StudentMemoRepository, target: MemoTarget): StudentMemo {
        val created = repo.create(target, MemoAnchor(.2f, .3f))
        return repo.replaceStrokes(target, created.id, created.revision, listOf(stroke(1)))
    }
    private fun stroke(id: Int) = MemoStroke("10000000-0000-0000-0000-${id.toString().padStart(12, '0')}",
        MemoTool.PEN, 0xff102030.toInt(), .004f,
        List(100) { MemoPoint((it % 99) / 99f, .2f, .3f) }, 1_000L)
    private fun inflations() = LosslessMemoPointCodec.inflationCount.get()
    private fun compressions() = LosslessMemoPointCodec.compressionCount.get()
    private companion object { const val MEMO_ID = "00000000-0000-0000-0000-000000000001" }
}
