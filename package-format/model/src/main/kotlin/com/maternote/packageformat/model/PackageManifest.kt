package com.maternote.packageformat.model

import kotlinx.serialization.Serializable

@Serializable data class FormatVersion(val major: Int, val minor: Int)
@Serializable data class CreatedBy(val application: String, val applicationVersion: String)
@Serializable data class BookDefinition(
    val bookId: String,
    val revisionId: String,
    val previousRevisionId: String? = null,
    val revisionNumber: Int,
    val title: String,
    val subtitle: String? = null,
    val coverAssetId: String? = null,
)
@Serializable data class AssetDefinition(
    val assetId: String,
    val path: String,
    val mimeType: String,
    val sha256: String,
    val byteSize: Long,
)
@Serializable data class DocumentDefinition(val type: String, val assetId: String)
@Serializable data class PageSource(val type: String, val pageIndex: Int? = null, val assetId: String? = null)
@Serializable data class PageDefinition(
    val pageId: String,
    val source: PageSource,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
)
@Serializable data class ActivityDefinition(
    val activityId: String,
    val title: String,
    val position: Int,
    val submissionMode: String = "INK_ONLY",
    val pageIds: List<String>,
)
@Serializable data class AnswerDocumentDefinition(val answerDocumentId: String, val assetId: String, val type: String = "ANSWER")
@Serializable data class AnswerLinkDefinition(val linkId: String, val answerDocumentId: String, val problemPageId: String?, val answerPageIndex: Int)
@Serializable data class ResourceDefinition(val resourceId: String, val title: String, val type: String, val text: String? = null, val imageAssetId: String? = null)
@Serializable data class PageResourceLinkDefinition(val linkId: String, val pageId: String, val resourceId: String)
@Serializable data class StructuredAnswerFieldDefinition(val fieldId: String, val pageId: String, val answerType: String)
@Serializable data class PageMapping(val oldPageId: String, val newPageId: String, val confidence: String)
@Serializable data class MigrationDefinition(val fromRevisionId: String, val pageMappings: List<PageMapping> = emptyList())

@Serializable
data class PackageManifest(
    val format: String = "maternote.book",
    val formatVersion: FormatVersion = FormatVersion(1, 0),
    val packageId: String,
    val createdAt: String,
    val createdBy: CreatedBy,
    val requiredCapabilities: List<String>,
    val optionalCapabilities: List<String> = emptyList(),
    val book: BookDefinition,
    val assets: List<AssetDefinition>,
    val document: DocumentDefinition,
    val pages: List<PageDefinition>,
    val activities: List<ActivityDefinition>,
    val answerDocuments: List<AnswerDocumentDefinition> = emptyList(),
    val answerLinks: List<AnswerLinkDefinition> = emptyList(),
    val teachingResources: List<ResourceDefinition> = emptyList(),
    val pageResourceLinks: List<PageResourceLinkDefinition> = emptyList(),
    val structuredAnswerFields: List<StructuredAnswerFieldDefinition> = emptyList(),
    val migration: MigrationDefinition? = null,
    val extensions: Map<String, String> = emptyMap(),
)
