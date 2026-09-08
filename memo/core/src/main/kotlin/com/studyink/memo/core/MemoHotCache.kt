package com.studyink.memo.core

import com.studyink.core.model.MasterNoteDataRootBus
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.IdentityHashMap

/** Bounded process-local acceleration only. Durable files and repository CAS remain authoritative.
 * Foreground focus alone determines the two pages; background reads never change that order. */
internal object MemoHotCaches {
    private val roots = LinkedHashMap<String, MemoRootHotCache>(4, .75f, true)
    @Synchronized fun forRoot(key: String): MemoRootHotCache {
        return roots.getOrPut(key) { MemoRootHotCache() }.also {
            while (roots.size > 4) roots.remove(roots.keys.first())?.clear()
        }
    }
    @Synchronized fun trim() { roots.values.forEach { it.clear() } }
}

internal class MemoRootHotCache {
    private data class PageKey(val book: String, val page: Int)
    private class Page {
        val snapshots = LinkedHashMap<String, Entry>()
        val points = MemoPointEncodingCache()
    }
    private data class Entry(val stamp: FileStamp, val snapshot: StudentMemoTargetSnapshot)
    private val pages = LinkedHashMap<PageKey, Page>()
    private val usages = LinkedHashMap<String, MemoUsage>(64, .75f, true)
    private var generation = MasterNoteDataRootBus.currentGeneration()

    @Synchronized fun focus(book: String, page: Int) {
        fresh()
        val key = PageKey(book, page)
        val value = pages.remove(key) ?: Page()
        pages[key] = value
        while (pages.size > 2) pages.remove(pages.keys.first())
    }
    @Synchronized fun points(target: MemoTarget): MemoPointEncodingCache? {
        fresh()
        return pages[PageKey(target.bookId, target.pageNumber)]?.points
    }
    @Synchronized fun find(file: File): StudentMemoTargetSnapshot? {
        fresh()
        val key = file.absolutePath
        val page = pages.values.firstOrNull { key in it.snapshots } ?: return null
        val entry = page.snapshots[key] ?: return null
        if (stamp(file) != entry.stamp) {
            page.snapshots.remove(key)
            prune(page)
            return null
        }
        return entry.snapshot
    }
    @Synchronized fun invalidate(file: File) {
        fresh()
        // Retain compressed bodies until a successful replacement snapshot can prune them.
        pages.values.forEach { it.snapshots.remove(file.absolutePath) }
    }
    @Synchronized fun remember(file: File, snapshot: StudentMemoTargetSnapshot) {
        fresh()
        val page = pages[PageKey(snapshot.target.bookId, snapshot.target.pageNumber)] ?: return
        val currentStamp = stamp(file) ?: return
        page.snapshots[file.absolutePath] = Entry(currentStamp, snapshot)
        while (page.snapshots.size > 16) page.snapshots.remove(page.snapshots.keys.first())
        // At most ~one million point objects across all attempts/roles, not two full copies per
        // repository instance. Teacher-base snapshots are excluded by the repository caller.
        while (pages.values.sumOf { p -> p.snapshots.values.sumOf { e -> pointCount(e.snapshot) } } > 1_000_000) {
            val oldest = pages.values.firstOrNull { it.snapshots.isNotEmpty() } ?: break
            oldest.snapshots.remove(oldest.snapshots.keys.first())
            prune(oldest)
        }
        prune(page)
    }
    @Synchronized fun usage(digest: String): MemoUsage? { fresh(); return usages[digest] }
    @Synchronized fun rememberUsage(memo: StudentMemo, bytes: Int): MemoUsage {
        fresh()
        return MemoUsage(bytes, memo.strokes.sumOf { it.points.size }, memo.strokes.size).also {
            usages[memo.digestSha256] = it
            while (usages.size > 64) usages.remove(usages.keys.first())
        }
    }
    @Synchronized fun clear() {
        pages.values.forEach { it.snapshots.clear(); it.points.clear() }
        usages.clear()
        generation = MasterNoteDataRootBus.currentGeneration()
    }
    @Synchronized fun pruneEncodings() { fresh(); pages.values.forEach(::prune) }
    private fun fresh() { if (generation != MasterNoteDataRootBus.currentGeneration()) clear() }
    private fun prune(page: Page) {
        page.points.retain(page.snapshots.values.flatMap { it.snapshot.memos }.flatMap { it.strokes }.map { it.points })
    }
    private fun pointCount(snapshot: StudentMemoTargetSnapshot) = snapshot.memos.sumOf { it.strokes.sumOf { s -> s.points.size } }
}

/** Identity keys are safe because only our frozen lists reach encoding. Incoming UI lists are
 * matched by stroke ID + exact point equality to existing frozen lists before validation. */
internal class MemoPointEncodingCache {
    private val encodings = IdentityHashMap<List<MemoPoint>, String>()
    private var bytes = 0L
    private var pointReferences = 0L
    @Synchronized fun encode(points: List<MemoPoint>): String = encodings[points]
        ?: LosslessMemoPointCodec.encode(points).also { remember(points, it) }
    @Synchronized fun remember(points: List<MemoPoint>, text: String) {
        if (encodings.containsKey(points)) return
        // Points are already retained by the snapshot: do not charge their object bodies twice.
        // The independent reference cap also bounds transient/failed-write entries until prune.
        val cost = text.length.toLong() * 2
        if (cost > MAX_BYTES) return
        if (bytes + cost > MAX_BYTES || pointReferences + points.size > 2_000_000L) clear()
        encodings[points] = text
        bytes += cost
        pointReferences += points.size
    }
    @Synchronized fun retain(points: List<List<MemoPoint>>) {
        val keep = IdentityHashMap<List<MemoPoint>, Boolean>()
        points.forEach { keep[it] = true }
        val iterator = encodings.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!keep.containsKey(entry.key)) {
                bytes -= entry.value.length.toLong() * 2
                pointReferences -= entry.key.size
                iterator.remove()
            }
        }
    }
    @Synchronized fun clear() { encodings.clear(); bytes = 0; pointReferences = 0 }
    private companion object { const val MAX_BYTES = 16L * 1024 * 1024 }
}

/** File identity catches atomic replacement even if size/mtime happen to match. Backup/new
 * files force the AtomicFile recovery path. The repository root guard serializes every writer. */
private data class FileStamp(val size: Long, val modified: String, val created: String, val key: String?)
private fun stamp(file: File): FileStamp? {
    if (File(file.path + ".bak").exists() || File(file.path + ".new").exists()) return null
    return runCatching {
        val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        FileStamp(attr.size(), attr.lastModifiedTime().toString(), attr.creationTime().toString(), attr.fileKey()?.toString())
    }.getOrNull()
}
