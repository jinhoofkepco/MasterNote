package com.studyink.assistant.core

import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import com.studyink.core.model.PageBounds
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ConcurrentModificationException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Standalone persistence for the optional GPT assistant feature.
 *
 * All files live below `<caller root>/gpt-assistant-v1`. The repository never opens or mutates the
 * MasterNote catalog, annotation log, Telegram settings, or any other feature file. Prompt data is
 * one atomic document, while teacher resources and student layers use one atomic document per
 * page/attempt so corruption cannot spread across pages.
 */
class AssistantRepository(
    rootDirectory: File,
    private val limits: AssistantStorageLimits = AssistantStorageLimits(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    private val dataRoot = rootDirectory
    private val featureRoot = File(rootDirectory, FEATURE_DIRECTORY)
    private val repositoryLock = lockFor(featureRoot)
    /** One bounded recovery scan per repository/process, unless a journal write actually fails. */
    private var publicationRecoveryCompleted = false
    private var publicationRecoveryScanCount = 0

    fun promptSlots(): List<AssistantPromptSlot> = locked { readPrompts() }

    fun promptSlot(slotNumber: Int): AssistantPromptSlot = locked {
        requireSlotNumber(slotNumber)
        readPrompts()[slotNumber - 1]
    }

    /** Updates one fixed slot without replacing or reordering the other three. */
    fun updatePromptSlot(
        slotNumber: Int,
        title: String,
        body: String,
    ): AssistantPromptSlot = locked {
        requireSlotNumber(slotNumber)
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        val current = readPrompts()
        val prior = current[slotNumber - 1]
        if (prior.title == cleanTitle && prior.body == cleanBody) return@locked prior
        check(prior.revision < Long.MAX_VALUE) { "Prompt revision is exhausted" }
        val updated = prior.copy(
            title = cleanTitle,
            body = cleanBody,
            revision = prior.revision + 1L,
            updatedAtEpochMillis = monotonicNow(prior.updatedAtEpochMillis),
        )
        AssistantValidation.prompt(updated, limits)
        val next = current.toMutableList().apply { this[slotNumber - 1] = updated }.toList()
        writePrompts(next)
        updated
    }

    fun resetPromptSlot(slotNumber: Int): AssistantPromptSlot = locked {
        requireSlotNumber(slotNumber)
        val seed = DefaultAssistantPrompts.slots[slotNumber - 1]
        updatePromptSlot(slotNumber, seed.title, seed.body)
    }

    fun listTeacherResources(page: AssistantPageKey): List<TeacherGptResourceSummary> = locked {
        readTeacherPage(page)
            .map { resource ->
                TeacherGptResourceSummary(
                    resourceId = resource.resourceId,
                    title = resource.title,
                    currentRevisionId = resource.currentRevisionId,
                    revisionCount = resource.revisions.size,
                    createdAtEpochMillis = resource.createdAtEpochMillis,
                    updatedAtEpochMillis = resource.currentRevision.createdAtEpochMillis,
                )
            }
            .sortedWith(compareByDescending<TeacherGptResourceSummary> { it.updatedAtEpochMillis }
                .thenBy { it.resourceId })
    }

    fun teacherResource(page: AssistantPageKey, resourceId: String): TeacherGptResource? = locked {
        AssistantValidation.id(resourceId, "resourceId")
        readTeacherPage(page).firstOrNull { it.resourceId == resourceId }
    }

    fun teacherResourceRevision(
        page: AssistantPageKey,
        resourceId: String,
        revisionId: String,
    ): TeacherGptResourceRevision? = locked {
        AssistantValidation.id(resourceId, "resourceId")
        AssistantValidation.id(revisionId, "revisionId")
        readTeacherPage(page)
            .firstOrNull { it.resourceId == resourceId }
            ?.revisions
            ?.firstOrNull { it.revisionId == revisionId }
    }

    /**
     * Saves the first immutable response revision and links it only to [page]. When supplied,
     * [promptTitleSnapshot]/[promptBodySnapshot] are the exact values already sent to the provider;
     * a later prompt-slot edit therefore cannot rewrite the answer's provenance.
     */
    fun createTeacherResource(
        page: AssistantPageKey,
        title: String,
        selectionBounds: PageBounds,
        promptSlotNumber: Int,
        answerText: String,
        answerHtml: String? = null,
        providerName: String? = null,
        promptTitleSnapshot: String? = null,
        promptBodySnapshot: String? = null,
        answerFormat: TeacherGptAnswerFormat = TeacherGptAnswerFormat.PLAIN_TEXT,
    ): TeacherGptResource = locked {
        requireSlotNumber(promptSlotNumber)
        require((promptTitleSnapshot == null) == (promptBodySnapshot == null)) {
            "Prompt title and body snapshots must be supplied together"
        }
        val cleanTitle = title.trim()
        val cleanAnswer = answerText.trim()
        val cleanHtml = answerHtml?.trim()?.takeIf(String::isNotEmpty)
        val cleanProvider = providerName?.trim()?.takeIf(String::isNotEmpty)
        val resources = readTeacherPage(page)
        require(resources.size < limits.maxTeacherResourcesPerPage) {
            "Too many teacher GPT resources on one page"
        }
        val prompt = readPrompts()[promptSlotNumber - 1]
        val recordedPromptTitle = promptTitleSnapshot?.trim()?.also {
            require(it.isNotEmpty()) { "Prompt title snapshot is blank" }
        } ?: prompt.title
        val recordedPromptBody = promptBodySnapshot?.trim()?.also {
            require(it.isNotEmpty()) { "Prompt body snapshot is blank" }
        } ?: prompt.body
        val usedIds = resources.flatMapTo(hashSetOf()) { resource ->
            listOf(resource.resourceId) + resource.revisions.map(TeacherGptResourceRevision::revisionId)
        }
        val resourceId = freshId(usedIds)
        usedIds += resourceId
        val revisionId = freshId(usedIds)
        val timestamp = validNow()
        val revision = TeacherGptResourceRevision(
            revisionId = revisionId,
            revisionNumber = 1L,
            promptSlotNumber = promptSlotNumber,
            promptTitle = recordedPromptTitle,
            promptBody = recordedPromptBody,
            selectionBounds = selectionBounds,
            answerText = cleanAnswer,
            answerHtml = cleanHtml,
            providerName = cleanProvider,
            createdAtEpochMillis = timestamp,
            answerFormat = answerFormat,
        )
        val resource = TeacherGptResource(
            resourceId = resourceId,
            page = page,
            title = cleanTitle,
            createdAtEpochMillis = timestamp,
            currentRevisionId = revisionId,
            revisions = listOf(revision),
        )
        val next = resources + resource
        AssistantValidation.teacherPage(page, next, limits)
        writeTeacherPage(page, next)
        resource
    }

    /** Appends a new response/edit; no API exists to mutate an older revision. */
    fun appendTeacherResourceRevision(
        page: AssistantPageKey,
        resourceId: String,
        selectionBounds: PageBounds,
        promptSlotNumber: Int,
        answerText: String,
        answerHtml: String? = null,
        providerName: String? = null,
        answerFormat: TeacherGptAnswerFormat = TeacherGptAnswerFormat.PLAIN_TEXT,
    ): TeacherGptResourceRevision = locked {
        requireSlotNumber(promptSlotNumber)
        AssistantValidation.id(resourceId, "resourceId")
        val resources = readTeacherPage(page)
        val index = resources.indexOfFirst { it.resourceId == resourceId }
        check(index >= 0) { "Unknown teacher GPT resource" }
        val prior = resources[index]
        require(prior.revisions.size < limits.maxRevisionsPerResource) {
            "Too many revisions for one teacher GPT resource"
        }
        check(prior.revisions.size.toLong() < Long.MAX_VALUE)
        val prompt = readPrompts()[promptSlotNumber - 1]
        val usedIds = resources.flatMapTo(hashSetOf()) { resource ->
            listOf(resource.resourceId) + resource.revisions.map(TeacherGptResourceRevision::revisionId)
        }
        val revision = TeacherGptResourceRevision(
            revisionId = freshId(usedIds),
            revisionNumber = prior.revisions.size + 1L,
            promptSlotNumber = promptSlotNumber,
            promptTitle = prompt.title,
            promptBody = prompt.body,
            selectionBounds = selectionBounds,
            answerText = answerText.trim(),
            answerHtml = answerHtml?.trim()?.takeIf(String::isNotEmpty),
            providerName = providerName?.trim()?.takeIf(String::isNotEmpty),
            createdAtEpochMillis = monotonicNow(prior.currentRevision.createdAtEpochMillis),
            answerFormat = answerFormat,
        )
        val updated = prior.copy(
            currentRevisionId = revision.revisionId,
            revisions = prior.revisions + revision,
        )
        val next = resources.toMutableList().apply { this[index] = updated }.toList()
        AssistantValidation.teacherPage(page, next, limits)
        writeTeacherPage(page, next)
        revision
    }

    /** Removes only this optional resource; student cards retain their copied explanation text. */
    fun removeTeacherResource(page: AssistantPageKey, resourceId: String): Boolean = locked {
        AssistantValidation.id(resourceId, "resourceId")
        val current = readTeacherPage(page)
        val next = current.filterNot { it.resourceId == resourceId }
        if (next.size == current.size) return@locked false
        writeTeacherPage(page, next)
        true
    }

    /** Creates an unsaved card after proving that its source revision belongs to this page. */
    fun newStudentExplanationCard(
        page: AssistantPageKey,
        sourceResourceId: String,
        sourceResourceRevisionId: String,
        title: String,
        text: String,
        anchorBounds: PageBounds,
    ): StudentExplanationCard = locked {
        val source = teacherResourceRevision(page, sourceResourceId, sourceResourceRevisionId)
        check(source != null) { "Student explanation source revision is not on this page" }
        val timestamp = validNow()
        StudentExplanationCard(
            cardId = freshId(emptySet()),
            sourceResourceId = sourceResourceId,
            sourceResourceRevisionId = sourceResourceRevisionId,
            title = title.trim(),
            text = text.trim(),
            anchorBounds = anchorBounds,
            createdAtEpochMillis = timestamp,
            updatedAtEpochMillis = timestamp,
        ).also { AssistantValidation.card(it, limits) }
    }

    fun studentExplanationLayer(target: StudentExplanationTarget): StudentExplanationLayer = locked {
        readStudentLayer(target)
    }

    /**
     * Stable teacher-side revision namespace shared by LAN and Telegram.
     *
     * Deleting/recreating only this optional feature creates a new epoch, so a fresh teacher
     * revision 1 cannot remain permanently stale behind a prior authority's larger revision.
     */
    fun teacherAuthorityEpoch(): String = locked {
        val file = authorityEpochFile()
        val existing = try {
            file.readOrNull()?.toString(Charsets.UTF_8)?.trim()
        } catch (error: Exception) {
            file.quarantineCorrupt()
            null
        }
        if (existing != null && SHA256_HEX.matches(existing)) return@locked existing
        if (existing != null) file.quarantineCorrupt()
        val epoch = identityDigest(
            "student-explanation-teacher-authority-v1",
            listOf(newUuid(), validNow().toString()),
        )
        file.write(epoch.toByteArray(Charsets.UTF_8))
        MasterNoteDataCommitBus.recordDurableCommit()
        epoch
    }

    /**
     * Locally publishes one exact-attempt full-state layer. [expectedRevision] prevents a stale UI
     * editor from overwriting a newer publish. Equal content is an idempotent no-op.
     */
    fun replaceStudentExplanationCards(
        target: StudentExplanationTarget,
        cards: List<StudentExplanationCard>,
        expectedRevision: Long? = null,
    ): StudentExplanationLayer = locked {
        val canonicalCards = cards.toList().sortedBy(StudentExplanationCard::cardId)
        val current = readStudentLayer(target)
        expectedRevision?.let {
            require(it >= 0L)
            if (it != current.revision) {
                throw ConcurrentModificationException(
                    "Student explanation layer changed from revision $it to ${current.revision}",
                )
            }
        }
        val proposedDigest = StudentExplanationDigest.sha256(target, canonicalCards)
        if (current.digestSha256 == proposedDigest && current.authorityEpoch == LOCAL_EXPLANATION_AUTHORITY) {
            if (current.revision > 0L && readPendingPublications().none {
                    it.target == current.target && it.revision == current.revision &&
                        it.digestSha256 == current.digestSha256 &&
                        it.resolvedAtEpochMillis == null
                }
            ) {
                val publishable = current.withAuthorityEpoch(PUBLISH_AUTHORITY_EPOCH_PLACEHOLDER)
                val checkpoint = AssistantJsonCodec.encodeStudentLayer(publishable)
                AssistantValidation.publishableStudentLayer(publishable, checkpoint)
                try {
                    recordPendingPublicationLocked(current)
                } catch (error: Throwable) {
                    publicationRecoveryCompleted = false
                    throw error
                }
            }
            return@locked current
        }
        check(current.revision < Long.MAX_VALUE) { "Student explanation revision is exhausted" }
        val next = StudentExplanationLayer(
            target = target,
            revision = current.revision + 1L,
            digestSha256 = proposedDigest,
            cards = canonicalCards,
            authorityEpoch = LOCAL_EXPLANATION_AUTHORITY,
            retiredAuthorityEpochs = successorRetiredAuthorities(
                current,
                LOCAL_EXPLANATION_AUTHORITY,
            ),
        )
        AssistantValidation.studentLayer(next, limits)
        val publishable = next.withAuthorityEpoch(PUBLISH_AUTHORITY_EPOCH_PLACEHOLDER)
        val checkpoint = AssistantJsonCodec.encodeStudentLayer(publishable)
        AssistantValidation.publishableStudentLayer(publishable, checkpoint)
        writeStudentLayer(next)
        // This second durable commit is intentionally recoverable: startup scans authoritative
        // local layer files and recreates a missing intent if the process dies between the writes.
        try {
            recordPendingPublicationLocked(next)
        } catch (error: Throwable) {
            publicationRecoveryCompleted = false
            throw error
        }
        next
    }

    /** Applies a peer full-state layer once, rejecting stale or same-revision/different-content data. */
    fun applyStudentExplanationLayer(
        expectedTarget: StudentExplanationTarget,
        incoming: StudentExplanationLayer,
    ): StudentLayerApplyResult = locked {
        require(incoming.target == expectedTarget) { "Student explanation target mismatch" }
        // Retirement history is receiver-owned state. Never let a peer add or remove replay fences.
        val canonical = incoming.copy(
            cards = incoming.cards.toList().sortedBy(StudentExplanationCard::cardId),
            retiredAuthorityEpochs = emptySet(),
        )
        AssistantValidation.studentLayer(canonical, limits)
        val current = readStudentLayer(expectedTarget)
        val status = when {
            canonical.authorityEpoch in current.retiredAuthorityEpochs ->
                StudentLayerApplyStatus.STALE
            canonical.authorityEpoch != current.authorityEpoch -> {
                val successor = canonical.copy(
                    retiredAuthorityEpochs = successorRetiredAuthorities(
                        current,
                        canonical.authorityEpoch,
                    ),
                )
                AssistantValidation.studentLayer(successor, limits)
                writeStudentLayer(successor)
                return@locked StudentLayerApplyResult(StudentLayerApplyStatus.APPLIED, successor)
            }
            canonical.revision < current.revision -> StudentLayerApplyStatus.STALE
            canonical.revision == current.revision &&
                canonical.digestSha256 == current.digestSha256 -> StudentLayerApplyStatus.ALREADY_CURRENT
            canonical.revision == current.revision -> StudentLayerApplyStatus.CONFLICT
            else -> {
                val successor = canonical.copy(
                    retiredAuthorityEpochs = current.retiredAuthorityEpochs,
                )
                writeStudentLayer(successor)
                return@locked StudentLayerApplyResult(StudentLayerApplyStatus.APPLIED, successor)
            }
        }
        StudentLayerApplyResult(status, current)
    }

    /** Stable, bounded checkpoint for LAN/Telegram transport. */
    fun exportStudentExplanationLayer(target: StudentExplanationTarget): ByteArray = locked {
        AssistantJsonCodec.encodeStudentLayer(readStudentLayer(target)).also {
            require(it.size <= limits.maxStudentLayerFileBytes)
        }
    }

    /** Re-reads the authoritative body and freezes it in one authenticated teacher namespace. */
    fun layerForPendingPublication(
        publicationId: String,
        authorityEpoch: String,
    ): StudentExplanationLayer? = locked {
        AssistantValidation.id(authorityEpoch, "student explanation authority epoch")
        val pending = readPendingPublications().firstOrNull {
            it.publicationId == publicationId && it.resolvedAtEpochMillis == null
        }
            ?: return@locked null
        val layer = readStudentLayer(pending.target)
        if (layer.revision != pending.revision || layer.digestSha256 != pending.digestSha256) {
            return@locked null
        }
        layer.withAuthorityEpoch(authorityEpoch).also { frozen ->
            val bytes = AssistantJsonCodec.encodeStudentLayer(frozen)
            AssistantValidation.publishableStudentLayer(frozen, bytes)
        }
    }

    fun exportPendingStudentExplanationPublication(
        publicationId: String,
        authorityEpoch: String,
    ): ByteArray? = locked {
        val pending = readPendingPublications().firstOrNull {
            it.publicationId == publicationId && it.resolvedAtEpochMillis == null
        }
            ?: return@locked null
        val layer = readStudentLayer(pending.target)
        if (layer.revision != pending.revision || layer.digestSha256 != pending.digestSha256) {
            return@locked null
        }
        val frozen = layer.withAuthorityEpoch(authorityEpoch)
        AssistantJsonCodec.encodeStudentLayer(frozen).also { bytes ->
            AssistantValidation.publishableStudentLayer(frozen, bytes)
        }
    }

    fun pendingStudentExplanationPublications(): List<PendingStudentExplanationPublication> = locked {
        val recovered = if (publicationRecoveryCompleted) {
            readPendingPublications()
        } else {
            recoverPendingPublicationsLocked()
        }
        recovered
            .filter { it.resolvedAtEpochMillis == null }
            .sortedWith(
            compareBy<PendingStudentExplanationPublication> { it.queuedAtEpochMillis }
                .thenBy { it.publicationId },
        )
    }

    /** Idempotently proves that the bus event has a durable exact-revision publication intent. */
    fun ensurePendingStudentExplanationPublication(
        layer: StudentExplanationLayer,
    ): PendingStudentExplanationPublication = locked {
        val current = readStudentLayer(layer.target)
        require(current.revision == layer.revision && current.digestSha256 == layer.digestSha256) {
            "Student explanation publication is not the authoritative layer"
        }
        val publishable = current.withAuthorityEpoch(PUBLISH_AUTHORITY_EPOCH_PLACEHOLDER)
        val checkpoint = AssistantJsonCodec.encodeStudentLayer(publishable)
        AssistantValidation.publishableStudentLayer(publishable, checkpoint)
        try {
            recordPendingPublicationLocked(current)
        } catch (error: Throwable) {
            publicationRecoveryCompleted = false
            throw error
        }
    }

    /** Rotates only transport correlation; target/revision/digest identity remains immutable. */
    fun advancePendingStudentExplanationDeliveryAttempt(
        publicationId: String,
    ): PendingStudentExplanationPublication? = locked {
        val values = readPendingPublications()
        val index = values.indexOfFirst {
            it.publicationId == publicationId && it.resolvedAtEpochMillis == null
        }
        if (index < 0) return@locked null
        val current = values[index]
        check(current.deliveryAttempt < Long.MAX_VALUE) { "Student explanation delivery attempt is exhausted" }
        val next = current.copy(deliveryAttempt = current.deliveryAttempt + 1L)
        writePendingPublications(values.toMutableList().apply { this[index] = next })
        next
    }

    fun advanceAllPendingStudentExplanationDeliveryAttempts(): List<PendingStudentExplanationPublication> = locked {
        val values = readPendingPublications()
        if (values.none { it.resolvedAtEpochMillis == null }) return@locked emptyList()
        val next = values.map { current ->
            if (current.resolvedAtEpochMillis != null) return@map current
            check(current.deliveryAttempt < Long.MAX_VALUE) {
                "Student explanation delivery attempt is exhausted"
            }
            current.copy(deliveryAttempt = current.deliveryAttempt + 1L)
        }
        writePendingPublications(next)
        next.filter { it.resolvedAtEpochMillis == null }
    }

    /** Resolves only the exact durable publication acknowledged by the receiving application. */
    fun resolvePendingStudentExplanationPublication(
        publicationId: String,
        target: StudentExplanationTarget,
        revision: Long,
        digestSha256: String,
    ): Boolean = locked {
        val values = readPendingPublications()
        val match = values.firstOrNull { publication ->
            publication.publicationId == publicationId && publication.target == target &&
                publication.revision == revision && publication.digestSha256 == digestSha256 &&
                publication.resolvedAtEpochMillis == null
        } ?: return@locked false
        val resolved = match.copy(
            resolvedAtEpochMillis = monotonicNow(match.queuedAtEpochMillis),
        )
        writePendingPublications(values.map { value ->
            if (value.publicationId == match.publicationId) resolved else value
        })
        true
    }

    fun decodeStudentExplanationLayer(bytes: ByteArray): StudentExplanationLayer {
        require(bytes.size <= limits.maxStudentLayerFileBytes) { "Student layer checkpoint is too large" }
        return AssistantJsonCodec.decodeStudentLayer(bytes.copyOf(), limits)
    }

    fun applyStudentExplanationLayer(
        expectedTarget: StudentExplanationTarget,
        checkpointBytes: ByteArray,
    ): StudentLayerApplyResult = applyStudentExplanationLayer(
        expectedTarget = expectedTarget,
        incoming = decodeStudentExplanationLayer(checkpointBytes),
    )

    private fun readPrompts(): List<AssistantPromptSlot> {
        val file = promptFile()
        val bytes = try {
            file.readOrNull()
        } catch (error: Exception) {
            file.quarantineCorrupt()
            return DefaultAssistantPrompts.slots
        } ?: return DefaultAssistantPrompts.slots
        return try {
            AssistantJsonCodec.decodePrompts(bytes, limits)
        } catch (error: Exception) {
            file.quarantineCorrupt()
            DefaultAssistantPrompts.slots
        }
    }

    private fun writePrompts(slots: List<AssistantPromptSlot>) {
        require(slots.size == DefaultAssistantPrompts.SLOT_COUNT)
        slots.forEach { AssistantValidation.prompt(it, limits) }
        val bytes = AssistantJsonCodec.encodePrompts(slots)
        promptFile().write(bytes)
        MasterNoteDataCommitBus.recordDurableCommit()
    }

    private fun readTeacherPage(page: AssistantPageKey): List<TeacherGptResource> {
        val file = teacherPageFile(page)
        val bytes = try {
            file.readOrNull()
        } catch (error: Exception) {
            file.quarantineCorrupt()
            return emptyList()
        } ?: return emptyList()
        return try {
            AssistantJsonCodec.decodeTeacherPage(bytes, page, limits)
        } catch (error: Exception) {
            file.quarantineCorrupt()
            emptyList()
        }
    }

    private fun writeTeacherPage(page: AssistantPageKey, resources: List<TeacherGptResource>) {
        AssistantValidation.teacherPage(page, resources, limits)
        val bytes = AssistantJsonCodec.encodeTeacherPage(page, resources)
        teacherPageFile(page).write(bytes)
        MasterNoteDataCommitBus.recordDurableCommit()
    }

    private fun readStudentLayer(target: StudentExplanationTarget): StudentExplanationLayer {
        val file = studentLayerFile(target)
        val bytes = try {
            file.readOrNull()
        } catch (error: Exception) {
            file.quarantineCorrupt()
            return emptyStudentLayer(target)
        } ?: return emptyStudentLayer(target)
        return try {
            AssistantJsonCodec.decodeStudentLayer(bytes, limits).also {
                require(it.target == target) { "Student explanation layer file identity mismatch" }
            }
        } catch (error: Exception) {
            file.quarantineCorrupt()
            emptyStudentLayer(target)
        }
    }

    private fun writeStudentLayer(layer: StudentExplanationLayer) {
        AssistantValidation.studentLayer(layer, limits)
        val bytes = AssistantJsonCodec.encodeStudentLayer(layer)
        studentLayerFile(layer.target).write(bytes)
        MasterNoteDataCommitBus.recordDurableCommit()
    }

    private fun recordPendingPublicationLocked(
        layer: StudentExplanationLayer,
    ): PendingStudentExplanationPublication {
        require(layer.revision > 0L)
        val values = readPendingPublications()
        val publicationId = studentExplanationPublicationId(
            layer.target,
            layer.revision,
            layer.digestSha256,
        )
        values.firstOrNull {
            it.publicationId == publicationId && it.resolvedAtEpochMillis == null
        }?.let { return it }
        val withoutTarget = values.filterNot { it.target == layer.target }
        require(withoutTarget.size < limits.maxPendingPublications) {
            "Too many pending student explanation publications"
        }
        val value = PendingStudentExplanationPublication(
            publicationId = publicationId,
            target = layer.target,
            revision = layer.revision,
            digestSha256 = layer.digestSha256,
            queuedAtEpochMillis = validNow(),
        ).also(AssistantValidation::pendingPublication)
        writePendingPublications(withoutTarget + value)
        return value
    }

    /**
     * Repairs the only unavoidable two-file crash window: a local layer may be durable while its
     * small publication journal entry is not. Resolved records remain as exact per-target
     * tombstones, so normal process restarts never re-send already acknowledged content.
     */
    private fun recoverPendingPublicationsLocked(): List<PendingStudentExplanationPublication> {
        publicationRecoveryScanCount += 1
        var values = readPendingPublications()
        var changed = false
        storedStudentLayersForRecovery().forEach { layer ->
            if (layer.authorityEpoch != LOCAL_EXPLANATION_AUTHORITY || layer.revision <= 0L) {
                return@forEach
            }
            val exactPublicationId = studentExplanationPublicationId(
                layer.target,
                layer.revision,
                layer.digestSha256,
            )
            if (values.any { it.publicationId == exactPublicationId }) return@forEach

            val publishable = layer.withAuthorityEpoch(PUBLISH_AUTHORITY_EPOCH_PLACEHOLDER)
            val checkpoint = AssistantJsonCodec.encodeStudentLayer(publishable)
            runCatching {
                AssistantValidation.publishableStudentLayer(publishable, checkpoint)
            }.getOrElse { return@forEach }
            val withoutTarget = values.filterNot { it.target == layer.target }
            if (withoutTarget.size >= limits.maxPendingPublications) return@forEach
            values = withoutTarget + PendingStudentExplanationPublication(
                publicationId = exactPublicationId,
                target = layer.target,
                revision = layer.revision,
                digestSha256 = layer.digestSha256,
                queuedAtEpochMillis = validNow(),
            ).also(AssistantValidation::pendingPublication)
            changed = true
        }
        if (changed) writePendingPublications(values)
        publicationRecoveryCompleted = true
        return values
    }

    private fun storedStudentLayersForRecovery(): List<StudentExplanationLayer> {
        val directory = File(featureRoot, "student-layers")
        if (!directory.isDirectory) return emptyList()
        val baseFiles = directory.walkTopDown()
            .filter(File::isFile)
            .mapNotNull { candidate ->
                when {
                    STUDENT_LAYER_BASE_FILE.matches(candidate.name) -> candidate
                    STUDENT_LAYER_BACKUP_FILE.matches(candidate.name) ->
                        File(requireNotNull(candidate.parentFile), candidate.name.removeSuffix(".bak"))
                    else -> null
                }
            }
            .distinctBy { it.toPath().toAbsolutePath().normalize().toString() }
            .sortedBy { it.path }
            .toList()
        return baseFiles.mapNotNull { baseFile ->
            val file = AtomicAssistantFile(baseFile, limits.maxStudentLayerFileBytes)
            try {
                val bytes = file.readOrNull() ?: return@mapNotNull null
                AssistantJsonCodec.decodeStudentLayer(bytes, limits).also { layer ->
                    val expected = studentLayerFile(layer.target).baseFileForTest()
                        .toPath().toAbsolutePath().normalize()
                    require(baseFile.toPath().toAbsolutePath().normalize() == expected) {
                        "Student explanation layer file identity mismatch"
                    }
                }
            } catch (error: Exception) {
                file.quarantineCorrupt()
                null
            }
        }
    }

    private fun readPendingPublications(): List<PendingStudentExplanationPublication> {
        val file = pendingPublicationFile()
        val bytes = try {
            file.readOrNull()
        } catch (error: Exception) {
            publicationRecoveryCompleted = false
            file.quarantineCorrupt()
            return emptyList()
        } ?: return emptyList()
        return try {
            AssistantJsonCodec.decodePendingPublications(bytes, limits)
        } catch (error: Exception) {
            publicationRecoveryCompleted = false
            file.quarantineCorrupt()
            emptyList()
        }
    }

    private fun writePendingPublications(values: List<PendingStudentExplanationPublication>) {
        require(values.size <= limits.maxPendingPublications)
        values.forEach(AssistantValidation::pendingPublication)
        pendingPublicationFile().write(AssistantJsonCodec.encodePendingPublications(values))
        MasterNoteDataCommitBus.recordDurableCommit()
    }

    private fun emptyStudentLayer(target: StudentExplanationTarget): StudentExplanationLayer =
        StudentExplanationLayer(
            target = target,
            revision = 0L,
            digestSha256 = StudentExplanationDigest.sha256(target, emptyList()),
            cards = emptyList(),
            authorityEpoch = LOCAL_EXPLANATION_AUTHORITY,
        )

    private fun successorRetiredAuthorities(
        current: StudentExplanationLayer,
        successorAuthorityEpoch: String,
    ): Set<String> {
        val retired = current.retiredAuthorityEpochs.toMutableSet()
        if (current.revision > 0L && current.authorityEpoch != successorAuthorityEpoch) {
            retired += current.authorityEpoch
        }
        retired -= successorAuthorityEpoch
        require(retired.size <= AssistantValidation.MAX_RETIRED_AUTHORITY_EPOCHS) {
            "Student explanation authority history is exhausted"
        }
        return retired.toSet()
    }

    private fun pendingPublicationFile(): AtomicAssistantFile = AtomicAssistantFile(
        File(featureRoot, "pending-publications-v1.json"),
        limits.maxPendingPublicationFileBytes,
    )

    private fun authorityEpochFile(): AtomicAssistantFile = AtomicAssistantFile(
        File(featureRoot, "teacher-authority-epoch-v1.txt"),
        256,
    )

    private fun promptFile(): AtomicAssistantFile = AtomicAssistantFile(
        File(featureRoot, "prompts-v1.json"),
        limits.maxPromptFileBytes,
    )

    private fun teacherPageFile(page: AssistantPageKey): AtomicAssistantFile = featureFile(
        directory = "teacher-pages",
        identity = listOf(page.bookId, page.pageNumber.toString()),
        maximumBytes = limits.maxTeacherPageFileBytes,
    )

    private fun studentLayerFile(target: StudentExplanationTarget): AtomicAssistantFile = featureFile(
        directory = "student-layers",
        identity = listOf(
            target.page.bookId,
            target.page.pageNumber.toString(),
            target.attemptNo.toString(),
        ),
        maximumBytes = limits.maxStudentLayerFileBytes,
    )

    private fun featureFile(
        directory: String,
        identity: List<String>,
        maximumBytes: Int,
    ): AtomicAssistantFile {
        val digest = identityDigest(directory, identity)
        return AtomicAssistantFile(
            File(featureRoot, "$directory/${digest.take(2)}/$digest.json"),
            maximumBytes,
        )
    }

    internal fun teacherPageFileForTest(page: AssistantPageKey): File =
        teacherPageFile(page).baseFileForTest()

    internal fun studentLayerFileForTest(target: StudentExplanationTarget): File =
        studentLayerFile(target).baseFileForTest()

    internal fun pendingPublicationFileForTest(): File = pendingPublicationFile().baseFileForTest()

    internal fun publicationRecoveryScanCountForTest(): Int = locked {
        publicationRecoveryScanCount
    }

    private fun freshId(used: Set<String>): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = newUuid()
            AssistantValidation.id(candidate, "generated ID")
            if (candidate !in used) return candidate
        }
        error("Unable to allocate a unique assistant feature ID")
    }

    private fun validNow(): Long = nowEpochMillis().also { require(it >= 0L) }

    private fun monotonicNow(prior: Long): Long = maxOf(validNow(), prior)

    private fun requireSlotNumber(slotNumber: Int) {
        require(slotNumber in 1..DefaultAssistantPrompts.SLOT_COUNT) { "Unknown prompt slot" }
    }

    private fun <T> locked(block: () -> T): T =
        MasterNoteOptionalDataRootGuard.withStableDataRoot(dataRoot) {
            synchronized(repositoryLock) { block() }
        }

    companion object {
        private const val FEATURE_DIRECTORY = "gpt-assistant-v1"
        private const val MAX_ID_GENERATION_ATTEMPTS = 32
        private const val PUBLISH_AUTHORITY_EPOCH_PLACEHOLDER =
            "0000000000000000000000000000000000000000000000000000000000000000"
        private val locks = ConcurrentHashMap<String, Any>()
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val STUDENT_LAYER_BASE_FILE = Regex("[0-9a-f]{64}\\.json")
        private val STUDENT_LAYER_BACKUP_FILE = Regex("[0-9a-f]{64}\\.json\\.bak")

        private fun lockFor(featureRoot: File): Any =
            locks.computeIfAbsent(featureRoot.toPath().toAbsolutePath().normalize().toString()) { Any() }

        private fun identityDigest(namespace: String, values: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            sequenceOf(namespace).plus(values.asSequence()).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun studentExplanationPublicationId(
            target: StudentExplanationTarget,
            revision: Long,
            digestSha256: String,
        ): String {
            require(revision > 0L)
            require(SHA256_HEX.matches(digestSha256))
            return identityDigest(
                "student-explanation-publication-v1",
                listOf(
                    target.page.bookId,
                    target.page.pageNumber.toString(),
                    target.attemptNo.toString(),
                    revision.toString(),
                    digestSha256,
                ),
            )
        }
    }
}
