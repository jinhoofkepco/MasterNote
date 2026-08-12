package com.studyink.annotation.storage

import androidx.room.withTransaction
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class LibraryBookStatus { ACTIVE, ARCHIVED, TRASHED }
data class LibraryFolder(val id: String, val parentId: String?, val name: String, val position: Int, val deletedAt: Long?)
data class LibraryBook(val id: String, val title: String, val subtitle: String?, val coverAssetId: String?, val currentRevisionId: String, val status: LibraryBookStatus)

class LibraryRepository internal constructor(private val database: AnnotationDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = database.libraryDao()
    suspend fun ensureRoot() = database.withTransaction {
        if (dao.folder(ROOT_ID) == null) dao.insertFolder(LibraryFolderEntity(ROOT_ID, null, "모든 책", ROOT_NAME, 0, now(), now(), null))
    }
    fun observeFolders(parentId: String = ROOT_ID): Flow<List<LibraryFolder>> = dao.observeFolders(parentId).map { rows -> rows.map(LibraryFolderEntity::model) }
    fun observeBooks(folderId: String = ROOT_ID): Flow<List<LibraryBook>> = dao.observeBooks(folderId).map { rows -> rows.map(LibraryBookEntity::model) }
    suspend fun createFolder(parentId: String, name: String): LibraryFolder = database.withTransaction {
        requireNotNull(dao.folder(parentId)) { "parent missing" }
        val normalized = normalizeName(name); require(normalized.isNotBlank()) { "empty folder name" }
        val t = now(); val entity = LibraryFolderEntity(UUID.randomUUID().toString(), parentId, cleanName(name), normalized, dao.nextFolderPosition(parentId), t, t, null)
        dao.insertFolder(entity); entity.model()
    }
    suspend fun renameFolder(id: String, name: String) = database.withTransaction {
        require(id != ROOT_ID) { "root is immutable" }; val old = requireNotNull(dao.folder(id)); val clean = cleanName(name); require(clean.isNotBlank()); dao.updateFolder(old.copy(displayName = clean, normalizedName = normalizeName(clean), updatedAtEpochMillis = now()))
    }
    suspend fun moveFolder(id: String, newParentId: String) = database.withTransaction {
        require(id != ROOT_ID && id != newParentId); val folder = requireNotNull(dao.folder(id)); requireNotNull(dao.folder(newParentId))
        val byId = dao.allFolders().associateBy { it.folderId }; var cursor: String? = newParentId
        while (cursor != null) { require(cursor != id) { "folder cycle" }; cursor = byId[cursor]?.parentFolderId }
        dao.updateFolder(folder.copy(parentFolderId = newParentId, position = dao.nextFolderPosition(newParentId), updatedAtEpochMillis = now()))
    }
    suspend fun trashFolder(id: String, moveContentsToParent: Boolean) = database.withTransaction {
        require(id != ROOT_ID); val folder = requireNotNull(dao.folder(id)); require(moveContentsToParent || (dao.activeChildCount(id) == 0 && dao.placedBookCount(id) == 0)) { "folder not empty" }
        if (moveContentsToParent) {
            val parent = requireNotNull(folder.parentFolderId)
            dao.allFolders().filter { it.parentFolderId == id && it.deletedAtEpochMillis == null }.forEach { dao.updateFolder(it.copy(parentFolderId = parent, position = dao.nextFolderPosition(parent), updatedAtEpochMillis = now())) }
            // Books are intentionally moved by relation only.
            val books = dao.observeBooks(id).first()
            books.forEach { dao.placeBook(BookPlacementEntity(it.bookId, parent, dao.nextBookPosition(parent), now())) }
        }
        dao.updateFolder(folder.copy(deletedAtEpochMillis = now(), updatedAtEpochMillis = now()))
    }
    suspend fun restoreFolder(id: String) = database.withTransaction { val f = requireNotNull(dao.folder(id)); dao.updateFolder(f.copy(deletedAtEpochMillis = null, updatedAtEpochMillis = now())) }
    suspend fun registerBook(bookId: String, title: String, revisionId: String, folderId: String): LibraryBook = database.withTransaction {
        requireNotNull(dao.folder(folderId)); requireNotNull(database.learningDao().bookRevision(revisionId))
        val existing = dao.book(bookId); val t = now()
        if (existing == null) dao.insertBook(LibraryBookEntity(bookId, title, null, null, revisionId, LibraryBookStatus.ACTIVE.name, t, t, null))
        else dao.updateBook(existing.copy(title = title, currentRevisionId = revisionId, status = LibraryBookStatus.ACTIVE.name, updatedAtEpochMillis = t))
        dao.placeBook(BookPlacementEntity(bookId, folderId, dao.nextBookPosition(folderId), t)); requireNotNull(dao.book(bookId)).model()
    }
    suspend fun moveBook(bookId: String, folderId: String) = database.withTransaction { requireNotNull(dao.book(bookId)); requireNotNull(dao.folder(folderId)); dao.placeBook(BookPlacementEntity(bookId, folderId, dao.nextBookPosition(folderId), now())) }
    suspend fun setBookStatus(bookId: String, status: LibraryBookStatus) = database.withTransaction { val b = requireNotNull(dao.book(bookId)); dao.updateBook(b.copy(status = status.name, archivedAtEpochMillis = if (status == LibraryBookStatus.ACTIVE) null else now(), updatedAtEpochMillis = now())) }
    suspend fun currentDocumentAssetId(bookId: String): ManagedAssetId { val book=requireNotNull(dao.book(bookId)); return ManagedAssetId(requireNotNull(dao.revisionSource(book.currentRevisionId)).documentAssetId) }
    suspend fun hasBook(bookId:String)=dao.book(bookId)!=null
    suspend fun registerRevisionSource(revisionId:String,documentAssetId:ManagedAssetId,sourceType:String="RAW_PDF")=database.withTransaction{if(dao.revisionSource(revisionId)==null)dao.insertRevisionSource(LibraryRevisionSourceEntity(revisionId,documentAssetId.value,documentAssetId.value,sourceType,"1.0",null,null,now()))}

    companion object {
        const val ROOT_ID = "ROOT"; private const val ROOT_NAME = "root"
        fun open(context: android.content.Context) = LibraryRepository(AnnotationDatabase.open(context))
        fun normalizeName(value: String) = Normalizer.normalize(cleanName(value), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        private fun cleanName(value: String) = value.trim().replace(Regex("\\s+"), " ")
    }
    fun close() = database.close()
}
private fun LibraryFolderEntity.model() = LibraryFolder(folderId, parentFolderId, displayName, position, deletedAtEpochMillis)
private fun LibraryBookEntity.model() = LibraryBook(bookId, title, subtitle, coverAssetId, currentRevisionId, LibraryBookStatus.valueOf(status))
