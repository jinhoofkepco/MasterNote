package com.studyink.annotation.storage

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class TeachingResourceType { TEXT, IMAGE, TEXT_AND_IMAGE }
enum class TeachingResourceCategory { VOCABULARY, SENTENCE_EXPLANATION, PASSAGE_EXPLANATION, GRAMMAR, CONCEPT, EXAMPLE, GENERAL }
enum class TeachingResourceVisibility { TEACHER_ONLY, STUDENT_ON_DEMAND, STUDENT_VISIBLE }
enum class TeachingResourceStatus { DRAFT, PUBLISHED, ARCHIVED }
enum class TeachingResourceSource { MANUAL, IMPORTED, ASSISTANT_EXTERNAL, ASSISTANT_API, CONTENT_PACKAGE }
enum class ResourceTriggerType { PAGE_RESOURCE_LIST, ANCHOR_HOTSPOT, MANUAL_ONLY }

data class TeachingResourceSummary(val resourceId: String, val title: String, val type: TeachingResourceType, val category: TeachingResourceCategory, val status: TeachingResourceStatus, val currentRevisionId: String?, val trigger: ResourceTriggerType)
data class TeachingResourceContent(val revisionId: String, val resourceId: String, val revisionNumber: Int, val text: String?, val structuredJson: String?, val imageAssetId: ManagedAssetId?, val sourcePrompt: String?, val providerName: String?)

class TeachingResourceRepository internal constructor(private val database: AnnotationDatabase, private val clock: () -> Long) {
    private val dao = database.teachingResourceDao()

    suspend fun createDraft(bookRevisionId: String, type: TeachingResourceType, category: TeachingResourceCategory, title: String, source: TeachingResourceSource, teacherId: String): String {
        require(title.isNotBlank() && title.length <= 120)
        check(database.learningDao().bookRevision(bookRevisionId) != null)
        val id = UUID.randomUUID().toString()
        val now = clock()
        dao.insertResource(TeachingResourceEntity(id, bookRevisionId, type.name, category.name, title.trim(), TeachingResourceVisibility.TEACHER_ONLY.name, TeachingResourceStatus.DRAFT.name, source.name, null, teacherId, now, now))
        return id
    }

    suspend fun addRevision(resourceId: String, text: String? = null, structuredJson: String? = null, imageAssetId: ManagedAssetId? = null, sourcePrompt: String? = null, providerName: String? = null): String {
        val resource = dao.resource(resourceId) ?: error("Unknown resource")
        check(resource.status != TeachingResourceStatus.ARCHIVED.name)
        require(!text.isNullOrBlank() || !structuredJson.isNullOrBlank() || imageAssetId != null) { "Text or image is required" }
        imageAssetId?.let { id ->
            val asset = database.managedAssetDao().asset(id.value) ?: error("Unknown image asset")
            require(asset.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
        }
        val id = UUID.randomUUID().toString()
        dao.addRevisionAndSelect(TeachingResourceRevisionEntity(id, resourceId, dao.maxRevision(resourceId) + 1, text?.trim(), structuredJson, imageAssetId?.value, sourcePrompt, providerName, clock()), clock())
        return id
    }

    suspend fun linkToPage(resourceId: String, pageId: String, anchor: CanonicalRect? = null, trigger: ResourceTriggerType = ResourceTriggerType.PAGE_RESOURCE_LIST, sortOrder: Int = 0): String {
        val resource = dao.resource(resourceId) ?: error("Unknown resource")
        require(trigger != ResourceTriggerType.ANCHOR_HOTSPOT || anchor != null)
        val id = UUID.randomUUID().toString()
        dao.insertLink(BookPageResourceLinkEntity(id, resource.bookRevisionId, pageId, resourceId, anchor?.left, anchor?.top, anchor?.right, anchor?.bottom, trigger.name, sortOrder, clock()))
        return id
    }

    suspend fun publish(resourceId: String) {
        val resource = dao.resource(resourceId) ?: error("Unknown resource")
        check(resource.currentRevisionId != null) { "Empty resource cannot be published" }
        check(dao.setStatus(resourceId, TeachingResourceStatus.PUBLISHED.name, clock()) == 1)
    }

    suspend fun archive(resourceId: String) { check(dao.setStatus(resourceId, TeachingResourceStatus.ARCHIVED.name, clock()) == 1) }

    fun observePageResources(revisionId: String, pageId: String): Flow<List<TeachingResourceSummary>> =
        dao.observePageResources(revisionId, pageId).map { rows -> rows.map { TeachingResourceSummary(it.resourceId, it.title, TeachingResourceType.valueOf(it.resourceType), TeachingResourceCategory.valueOf(it.category), TeachingResourceStatus.valueOf(it.status), it.currentRevisionId, ResourceTriggerType.valueOf(it.triggerType)) } }

    suspend fun getResourceRevision(revisionId: String): TeachingResourceContent {
        val row = dao.revision(revisionId) ?: error("Unknown resource revision")
        return TeachingResourceContent(row.revisionId, row.resourceId, row.revisionNumber, row.textContent, row.structuredContentJson, row.imageAssetId?.let(::ManagedAssetId), row.sourcePrompt, row.providerName)
    }

    fun close() = database.close()
    companion object { fun open(context: Context, clock: () -> Long = System::currentTimeMillis) = TeachingResourceRepository(AnnotationDatabase.open(context), clock) }
}
