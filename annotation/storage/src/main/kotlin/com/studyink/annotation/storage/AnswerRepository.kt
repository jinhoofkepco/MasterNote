package com.studyink.annotation.storage

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AnswerDocumentType { PDF, IMAGE_SEQUENCE }
enum class AnswerKind { ANSWER, EXPLANATION, SCRIPT, TEACHER_GUIDE, OTHER }
enum class AnswerLocationSource { REGION, PAGE, ACTIVITY, BOOKMARK, FIRST_PAGE }

data class CanonicalRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f)
        require(left < right && top < bottom)
    }

    fun overlaps(other: CanonicalRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

data class AnswerDocument(
    val id: String,
    val bookRevisionId: String,
    val assetId: ManagedAssetId,
    val type: AnswerDocumentType,
    val kind: AnswerKind,
    val pageCount: Int,
    val displayName: String,
)

data class AnswerLocation(
    val answerDocumentId: String,
    val pageIndex: Int,
    val region: CanonicalRect?,
    val source: AnswerLocationSource,
    val bookmark: AnswerBookmark? = null,
)

data class AnswerBookmark(
    val pageIndex: Int,
    val normalizedCenterX: Float,
    val normalizedCenterY: Float,
    val zoomScale: Float,
)

data class OffsetLinkPreview(
    val problemPageId: String,
    val answerPageIndex: Int,
    val valid: Boolean,
)

class AnswerRepository internal constructor(
    private val database: AnnotationDatabase,
    private val clock: () -> Long,
) {
    private val dao = database.answerDao()

    fun observeDocuments(bookRevisionId: String): Flow<List<AnswerDocument>> =
        dao.observeDocuments(bookRevisionId).map { rows -> rows.map(AnswerDocumentEntity::toModel) }

    suspend fun linkAnswerDocument(
        bookRevisionId: String,
        assetId: ManagedAssetId,
        kind: AnswerKind,
        displayName: String,
    ): String {
        require(displayName.isNotBlank())
        check(database.learningDao().bookRevision(bookRevisionId) != null) { "Unknown book revision" }
        val asset = database.managedAssetDao().asset(assetId.value) ?: error("Unknown asset")
        val type = when (asset.mimeType) {
            "application/pdf" -> AnswerDocumentType.PDF
            "application/zip" -> AnswerDocumentType.IMAGE_SEQUENCE
            else -> error("Answer documents must be PDF or image ZIP")
        }
        val pageCount = asset.pageCount ?: error("Validated answer document has no page count")
        require(pageCount > 0)
        val id = UUID.randomUUID().toString()
        dao.insertDocument(
            AnswerDocumentEntity(
                answerDocumentId = id,
                bookRevisionId = bookRevisionId,
                assetId = assetId.value,
                documentType = type.name,
                answerKind = kind.name,
                pageCount = pageCount,
                displayName = displayName.trim(),
                isActive = true,
                linkedAtEpochMillis = clock(),
            )
        )
        return id
    }

    suspend fun savePageLink(
        bookRevisionId: String,
        answerDocumentId: String,
        activityId: String?,
        problemPageId: String?,
        problemRegion: CanonicalRect?,
        answerPageIndex: Int,
        answerRegion: CanonicalRect? = null,
        sortOrder: Int = 0,
    ): String {
        validateLinkTarget(bookRevisionId, answerDocumentId, activityId, problemPageId, answerPageIndex)
        require(problemRegion == null || problemPageId != null)
        val now = clock()
        val id = UUID.randomUUID().toString()
        dao.insertLink(
            linkEntity(id, bookRevisionId, answerDocumentId, activityId, problemPageId, problemRegion, answerPageIndex, answerRegion, sortOrder, now)
        )
        return id
    }

    suspend fun previewOffsetLinks(
        activityId: String,
        answerDocumentId: String,
        firstAnswerPageIndex: Int,
    ): List<OffsetLinkPreview> {
        val activity = database.learningDao().activity(activityId) ?: error("Unknown activity")
        val document = dao.document(answerDocumentId) ?: error("Unknown answer document")
        check(activity.revisionId == document.bookRevisionId)
        return database.learningDao().activityPages(activityId).mapIndexed { index, page ->
            val answerPage = firstAnswerPageIndex + index
            OffsetLinkPreview(page.pageId, answerPage, answerPage in 0 until document.pageCount)
        }
    }

    suspend fun saveOffsetLinks(
        activityId: String,
        answerDocumentId: String,
        firstAnswerPageIndex: Int,
    ): List<String> {
        val preview = previewOffsetLinks(activityId, answerDocumentId, firstAnswerPageIndex)
        require(preview.isNotEmpty() && preview.all { it.valid }) { "Offset mapping exceeds answer document" }
        val document = requireNotNull(dao.document(answerDocumentId))
        val now = clock()
        val ids = preview.map { UUID.randomUUID().toString() }
        dao.insertValidatedLinks(
            preview.zip(ids).mapIndexed { index, (item, id) ->
                linkEntity(id, document.bookRevisionId, answerDocumentId, activityId, item.problemPageId, null, item.answerPageIndex, null, index, now)
            }
        )
        return ids
    }

    suspend fun resolveAnswerLocation(
        teacherId: String,
        bookRevisionId: String,
        activityId: String?,
        problemPageId: String?,
        selectedRegion: CanonicalRect?,
        preferredDocumentId: String? = null,
    ): AnswerLocation {
        val documents = dao.documents(bookRevisionId)
        val document = preferredDocumentId?.let { id -> documents.firstOrNull { it.answerDocumentId == id } }
            ?: documents.firstOrNull { it.answerKind == AnswerKind.ANSWER.name }
            ?: documents.firstOrNull()
            ?: error("No active answer document")

        val pageLinks = problemPageId?.let { dao.pageLinks(bookRevisionId, document.answerDocumentId, it) }.orEmpty()
        if (selectedRegion != null) {
            pageLinks.firstOrNull { it.problemRect()?.overlaps(selectedRegion) == true }?.let {
                return it.location(AnswerLocationSource.REGION)
            }
        }
        pageLinks.firstOrNull { it.problemRect() == null }?.let { return it.location(AnswerLocationSource.PAGE) }
        if (activityId != null) {
            dao.activityLinks(bookRevisionId, document.answerDocumentId, activityId).firstOrNull()?.let {
                return it.location(AnswerLocationSource.ACTIVITY)
            }
        }
        dao.bookmark(teacherId, document.answerDocumentId)?.let {
            val bookmark = AnswerBookmark(it.pageIndex, it.normalizedCenterX, it.normalizedCenterY, it.zoomScale)
            return AnswerLocation(document.answerDocumentId, it.pageIndex, null, AnswerLocationSource.BOOKMARK, bookmark)
        }
        return AnswerLocation(document.answerDocumentId, 0, null, AnswerLocationSource.FIRST_PAGE)
    }

    suspend fun saveBookmark(teacherId: String, answerDocumentId: String, bookmark: AnswerBookmark) {
        val document = dao.document(answerDocumentId) ?: error("Unknown answer document")
        require(bookmark.pageIndex in 0 until document.pageCount)
        require(bookmark.normalizedCenterX in 0f..1f && bookmark.normalizedCenterY in 0f..1f)
        require(bookmark.zoomScale.isFinite() && bookmark.zoomScale >= 1f)
        dao.upsertBookmark(
            AnswerBookmarkEntity(teacherId, answerDocumentId, bookmark.pageIndex, bookmark.normalizedCenterX, bookmark.normalizedCenterY, bookmark.zoomScale, clock())
        )
    }

    fun close() = database.close()

    private suspend fun validateLinkTarget(revisionId: String, documentId: String, activityId: String?, pageId: String?, answerPage: Int) {
        val document = dao.document(documentId) ?: error("Unknown answer document")
        check(document.bookRevisionId == revisionId && document.isActive) { "Answer document belongs to another revision" }
        require(answerPage in 0 until document.pageCount)
        if (activityId != null) check(database.learningDao().activity(activityId)?.revisionId == revisionId) { "Activity belongs to another revision" }
        if (pageId != null && activityId != null) check(database.learningDao().activityPages(activityId).any { it.pageId == pageId }) { "Page is not in activity" }
        require(activityId != null || pageId != null) { "A page or activity target is required" }
    }

    companion object {
        fun open(context: Context, clock: () -> Long = System::currentTimeMillis): AnswerRepository =
            AnswerRepository(AnnotationDatabase.open(context), clock)
    }
}

private fun AnswerDocumentEntity.toModel() = AnswerDocument(answerDocumentId, bookRevisionId, ManagedAssetId(assetId), AnswerDocumentType.valueOf(documentType), AnswerKind.valueOf(answerKind), pageCount, displayName)
private fun AnswerPageLinkEntity.problemRect() = rect(problemLeft, problemTop, problemRight, problemBottom)
private fun AnswerPageLinkEntity.answerRect() = rect(answerLeft, answerTop, answerRight, answerBottom)
private fun AnswerPageLinkEntity.location(source: AnswerLocationSource) = AnswerLocation(answerDocumentId, answerPageIndex, answerRect(), source)
private fun rect(left: Float?, top: Float?, right: Float?, bottom: Float?): CanonicalRect? =
    if (left == null || top == null || right == null || bottom == null) null else CanonicalRect(left, top, right, bottom)

private fun linkEntity(id: String, revisionId: String, documentId: String, activityId: String?, pageId: String?, problem: CanonicalRect?, answerPage: Int, answer: CanonicalRect?, sortOrder: Int, now: Long) =
    AnswerPageLinkEntity(id, revisionId, documentId, activityId, pageId, problem?.left, problem?.top, problem?.right, problem?.bottom, answerPage, answer?.left, answer?.top, answer?.right, answer?.bottom, sortOrder, now, now)
