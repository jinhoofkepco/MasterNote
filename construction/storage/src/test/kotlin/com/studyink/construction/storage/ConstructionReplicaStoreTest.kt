package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryPoint
import com.studyink.core.model.MasterNoteDataRootBus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.ConcurrentModificationException
import java.util.UUID

class ConstructionReplicaStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `legacy scene imports once without rewriting original and empty attachment survives reopen`() {
        val root = temporary.newFolder("data")
        val legacy = ConstructionSceneStore(root)
        legacy.save(legacy.load(TARGET), scene(2.0))
        val original = legacy.targetFileForTest(TARGET).readBytes()
        val store = ConstructionReplicaStore(root)
        assertEquals(scene(2.0), store.load(TARGET, STUDENT).scene)
        assertTrue(store.hasAttachment(TARGET, STUDENT))
        store.saveLocal(store.load(TARGET, STUDENT), scene(3.0))
        assertArrayEquals(original, legacy.targetFileForTest(TARGET).readBytes())
        assertEquals(scene(3.0), ConstructionReplicaStore(root).load(TARGET, STUDENT).scene)
        val another = TARGET.copy(attemptNo = 2)
        assertFalse(store.hasAttachment(another, STUDENT))
        store.ensureAttachment(another, STUDENT)
        assertTrue(ConstructionReplicaStore(root).hasAttachment(another, STUDENT))
        assertEquals(ConstructionScene(), store.load(another, STUDENT).scene)
    }

    @Test fun `student snapshot adopts clean teacher while dirty draft and common base remain separate`() {
        val (student, teacher) = pair()
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        val base = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET))
        assertEquals(scene(1.0), base.scene)
        assertFalse(base.draftDirty)
        val draft = teacher.saveLocal(base, scene(2.0))
        assertTrue(draft.draftDirty)
        student.saveLocal(student.load(TARGET, STUDENT), scene(3.0))
        val changed = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET))
        assertEquals(scene(2.0), changed.scene)
        assertEquals(scene(1.0), changed.commonBase!!.scene)
        assertEquals(scene(3.0), changed.studentShadow!!.scene)
        assertTrue(teacher.preparePublish(changed).conflict)
    }

    @Test fun `publish persists recovery and duplicate receipt before returning applied acknowledgement`() {
        val (student, teacher) = pair()
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        val base = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET))
        val draft = teacher.saveLocal(base, scene(2.0))
        val request = teacher.preparePublish(draft).packet!!
        val response = student.receivePublish(TARGET, request)
        assertEquals(ConstructionPublishResult.APPLIED, response.result)
        val committed = student.load(TARGET, STUDENT)
        assertEquals(scene(2.0), committed.scene)
        assertEquals(scene(1.0), committed.recoveryScene!!.scene)
        assertEquals(response, student.receivePublish(TARGET, request))
        assertEquals(committed.studentShadow!!.version, student.load(TARGET, STUDENT).studentShadow!!.version)
        val teacherResult = teacher.receiveResult(TARGET, response)
        assertFalse(teacherResult.draftDirty)
        assertNull(teacherResult.pendingPublish)
        assertEquals(response.student, teacherResult.commonBase)
    }

    @Test fun `comparison choices use exact student token and another student edit conflicts atomically`() {
        val (student, teacher) = pair()
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        student.saveLocal(student.load(TARGET, STUDENT), scene(3.0))
        val comparison = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET))
        val overwrite = teacher.preparePublish(comparison, comparison.studentShadow!!.version).packet!!
        student.saveLocal(student.load(TARGET, STUDENT), scene(4.0))
        val conflict = student.receivePublish(TARGET, overwrite)
        assertEquals(ConstructionPublishResult.CONFLICT, conflict.result)
        assertEquals(scene(4.0), student.load(TARGET, STUDENT).scene)
        val latest = teacher.receiveResult(TARGET, conflict)
        assertEquals(scene(2.0), latest.scene)
        assertThrows(ConcurrentModificationException::class.java) { teacher.adoptStudent(latest, comparison.studentShadow!!.version) }
        val adopted = teacher.adoptStudent(latest, latest.studentShadow!!.version)
        assertEquals(scene(4.0), adopted.scene)
        assertFalse(adopted.draftDirty)
    }

    @Test fun `teacher initial local draft cannot replace a nonempty student without comparison`() {
        val (student, teacher) = pair()
        teacher.saveLocal(teacher.load(TARGET, TEACHER), scene(8.0))
        student.saveLocal(student.load(TARGET, STUDENT), scene(9.0))
        val state = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET))
        assertNull(state.commonBase)
        assertTrue(teacher.preparePublish(state).conflict)
        assertNotNull(teacher.preparePublish(state, state.studentShadow!!.version).packet)
    }

    @Test fun `stale and reordered student snapshots never roll back shadow`() {
        val (student, teacher) = pair()
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        val old = student.studentSnapshot(TARGET)
        student.saveLocal(student.load(TARGET, STUDENT), scene(2.0))
        teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET))
        val afterOld = teacher.receiveStudentSnapshot(TARGET, old)
        assertEquals(scene(2.0), afterOld.scene)
        val another = old.copy(memoId = UUID.randomUUID().toString())
        assertThrows(IllegalArgumentException::class.java) { teacher.receiveStudentSnapshot(TARGET, another) }
    }

    @Test fun `backup restore generation invalidates equal revision publication and editor CAS`() {
        val (student, teacher) = pair()
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        val editorBase = student.sceneAccess(STUDENT).load(TARGET)
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        val request = teacher.preparePublish(draft).packet!!
        val oldGeneration = student.load(TARGET, STUDENT).studentShadow!!.version.generation
        MasterNoteDataRootBus.dataRootReplaced()
        val response = student.receivePublish(TARGET, request)
        assertEquals(ConstructionPublishResult.CONFLICT, response.result)
        assertTrue(response.student!!.version.generation > oldGeneration)
        assertEquals(scene(1.0), student.load(TARGET, STUDENT).scene)
        assertThrows(ConcurrentModificationException::class.java) { student.sceneAccess(STUDENT).save(editorBase, scene(7.0)) }
    }

    @Test fun `tombstone blocks late publish and local edits while preserving original legacy bytes`() {
        val (student, teacher) = pair()
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        val request = teacher.preparePublish(draft).packet!!
        val deleted = student.markMemoDeleted(TARGET, STUDENT)
        assertFalse(deleted.attached)
        assertTrue(deleted.deleted)
        assertEquals(ConstructionPublishResult.DELETED, student.receivePublish(TARGET, request).result)
        assertThrows(IllegalStateException::class.java) { student.saveLocal(student.load(TARGET, STUDENT), scene(3.0)) }
        assertTrue(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)).deleted)
    }

    @Test fun `same publication id with changed contents is rejected and forged ack cannot clear pending`() {
        val (student, teacher) = pair()
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        val request = teacher.preparePublish(draft).packet!!
        val response = student.receivePublish(TARGET, request)
        assertThrows(IllegalArgumentException::class.java) { student.receivePublish(TARGET, request.copy(scene = scene(5.0))) }
        val unrelated = response.copy(requestId = UUID.randomUUID().toString())
        assertNotNull(teacher.receiveResult(TARGET, unrelated).pendingPublish)
        val invalid = response.copy(student = response.student!!.copy(version = response.student!!.version.copy(revision = 0)))
        assertThrows(IllegalArgumentException::class.java) { teacher.receiveResult(TARGET, invalid) }
        assertNotNull(teacher.load(TARGET, TEACHER).pendingPublish)
    }

    @Test fun `corrupt replica is reported without silently reimporting old geometry`() {
        val root = temporary.newFolder("data")
        val store = ConstructionReplicaStore(root)
        store.saveLocal(store.load(TARGET, STUDENT), scene(2.0))
        val file = store.fileForTest(TARGET, STUDENT)
        file.writeText("corrupted replica")
        assertThrows(ConstructionDataException::class.java) { store.load(TARGET, STUDENT) }
        assertEquals("corrupted replica", file.readText())
    }

    @Test fun `wire roundtrip checks digest and attachment identity and packet bound`() {
        val (student, _) = pair()
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.25))
        val packet = student.studentSnapshot(TARGET)
        assertEquals(packet, ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(packet)))
        val forged = packet.copy(student = packet.student!!.copy(scene = scene(99.0)))
        assertThrows(IllegalArgumentException::class.java) { ConstructionSyncCodec.encode(forged) }
        assertThrows(IllegalArgumentException::class.java) { ConstructionSyncCodec.decode(ByteArray(ConstructionSyncCodec.MAX_PACKET_BYTES + 1)) }
    }

    @Test fun `change bus distinguishes teacher draft from student durable content and adapter rejects stale scene`() {
        val (student, teacher) = pair()
        val changes = mutableListOf<ConstructionReplicaChange>()
        val subscription = ConstructionReplicaChangeBus.addListener { changes += it }
        try {
            val access = student.sceneAccess(STUDENT)
            val first = access.load(TARGET)
            access.save(first, scene(1.0))
            assertThrows(ConcurrentModificationException::class.java) { access.save(first, scene(5.0)) }
            teacher.saveLocal(teacher.load(TARGET, TEACHER), scene(2.0))
            assertEquals(listOf(STUDENT, TEACHER), changes.map { it.role })
            assertTrue(changes.all { it.kind == ConstructionReplicaChangeKind.LOCAL_EDIT })
            assertTrue(changes.all { it.snapshot.attached })
        } finally { subscription.close() }
    }

    @Test fun `teacher can continue draft while publication is pending and ack preserves that newer draft`() {
        val (student, teacher) = pair()
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        val prepared = teacher.preparePublish(draft)
        val newerDraft = teacher.saveLocal(prepared.snapshot, scene(3.0))
        assertEquals(scene(3.0), newerDraft.scene)
        assertEquals(prepared.packet, newerDraft.pendingPublish)
        val response = student.receivePublish(TARGET, prepared.packet!!)
        val done = teacher.receiveResult(TARGET, response)
        assertEquals(scene(2.0), done.commonBase!!.scene)
        assertEquals(scene(3.0), done.scene)
        assertTrue(done.draftDirty)
        assertNull(done.pendingPublish)
    }

    @Test fun `reopening stores retains pending publication and durable duplicate receipt and enumerates roles`() {
        val studentRoot = temporary.newFolder("student")
        val teacherRoot = temporary.newFolder("teacher")
        val student = ConstructionReplicaStore(studentRoot)
        val teacher = ConstructionReplicaStore(teacherRoot)
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        val request = teacher.preparePublish(draft).packet!!
        val response = student.receivePublish(TARGET, request)
        val reopenedStudent = ConstructionReplicaStore(studentRoot)
        val reopenedTeacher = ConstructionReplicaStore(teacherRoot)
        assertEquals(request, reopenedTeacher.load(TARGET, TEACHER).pendingPublish)
        assertEquals(response, reopenedStudent.receivePublish(TARGET, request))
        assertEquals(listOf(TARGET), reopenedStudent.listTargets(STUDENT))
        assertTrue(reopenedStudent.listTargets(TEACHER).isEmpty())
        assertEquals(listOf(TARGET), reopenedTeacher.listTargets(TEACHER))
        assertEquals(response.student!!.version, reopenedStudent.load(TARGET, STUDENT).studentShadow!!.version)
    }

    @Test fun `restore before first replica initialization is included in its generation`() {
        val root = temporary.newFolder("late")
        val before = MasterNoteDataRootBus.currentGeneration()
        MasterNoteDataRootBus.dataRootReplaced()
        val student = ConstructionReplicaStore(root).load(TARGET, STUDENT)
        assertEquals(before + 2L, student.studentShadow!!.version.generation)
    }

    @Test fun `actual old backup bytes cannot validate an old publish token after restoration`() {
        val studentRoot = temporary.newFolder("student")
        val student = ConstructionReplicaStore(studentRoot)
        val teacher = ConstructionReplicaStore(temporary.newFolder("teacher"))
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        val oldBytes = student.fileForTest(TARGET, STUDENT).readBytes()
        val oldSnapshot = student.studentSnapshot(TARGET)
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, oldSnapshot), scene(2.0))
        val oldRequest = teacher.preparePublish(draft).packet!!
        student.saveLocal(student.load(TARGET, STUDENT), scene(7.0))
        student.fileForTest(TARGET, STUDENT).writeBytes(oldBytes)
        MasterNoteDataRootBus.dataRootReplaced()
        val restored = student.studentSnapshot(TARGET)
        assertEquals(oldSnapshot.student!!.version.revision, restored.student!!.version.revision)
        assertEquals(oldSnapshot.student!!.version.digestSha256, restored.student!!.version.digestSha256)
        assertTrue(restored.student!!.version.generation > oldSnapshot.student!!.version.generation)
        assertEquals(ConstructionPublishResult.CONFLICT, student.receivePublish(TARGET, oldRequest).result)
        teacher.receiveStudentSnapshot(TARGET, restored)
        assertEquals(restored.student, teacher.receiveStudentSnapshot(TARGET, oldSnapshot).studentShadow)
    }

    @Test fun `new shadow invalidates both stale comparison choices before publication is prepared`() {
        val (student, teacher) = pair()
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        student.saveLocal(student.load(TARGET, STUDENT), scene(3.0))
        val compared = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET))
        student.saveLocal(student.load(TARGET, STUDENT), scene(4.0))
        teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET))
        assertThrows(ConcurrentModificationException::class.java) { teacher.preparePublish(compared, compared.studentShadow!!.version) }
        assertThrows(ConcurrentModificationException::class.java) { teacher.adoptStudent(compared, compared.studentShadow!!.version) }
        assertEquals(scene(2.0), teacher.load(TARGET, TEACHER).scene)
    }

    @Test fun `old successful publication receipts remain idempotent beyond a small recent message cache`() {
        val student = ConstructionReplicaStore(temporary.newFolder("student"))
        var original: ConstructionSyncPacket? = null
        var acknowledgement: ConstructionSyncPacket? = null
        repeat(20) { index ->
            val current = student.studentSnapshot(TARGET).student!!
            val request = ConstructionSyncPacket(ConstructionPacketKind.PUBLISH, UUID.randomUUID().toString(),
                TARGET.memoId, TARGET.pageNumber, TARGET.attemptNo, expectedStudent = current.version, scene = scene(index.toDouble()))
            val response = student.receivePublish(TARGET, request)
            if (index == 0) { original = request; acknowledgement = response }
        }
        val latest = student.load(TARGET, STUDENT)
        assertEquals(acknowledgement, student.receivePublish(TARGET, original!!))
        assertEquals(latest.studentShadow, student.load(TARGET, STUDENT).studentShadow)
        assertThrows(IllegalArgumentException::class.java) { student.receivePublish(TARGET, original!!.copy(scene = scene(555.0))) }
    }

    @Test fun `editor can rebase metadata only publication preparation while actual content CAS remains strict`() {
        val (student, teacher) = pair()
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        val access = teacher.sceneAccess(TEACHER)
        val openEditor = access.load(TARGET)
        val prepared = teacher.preparePublish(draft)
        val edited = access.save(openEditor, scene(3.0))
        assertEquals(scene(3.0), edited.scene)
        assertNotNull(teacher.load(TARGET, TEACHER).pendingPublish)
        assertThrows(ConcurrentModificationException::class.java) { access.save(openEditor, scene(4.0)) }
        val reply = student.receivePublish(TARGET, prepared.packet!!)
        teacher.receiveResult(TARGET, reply)
        assertEquals(scene(5.0), access.save(edited, scene(5.0)).scene)
        assertTrue(teacher.load(TARGET, TEACHER).draftDirty)
    }

    @Test fun `late applied ack adopts already received newer student shadow when teacher draft was unchanged`() {
        val (student, teacher) = pair()
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        val request = teacher.preparePublish(draft).packet!!
        val delayedAck = student.receivePublish(TARGET, request)
        student.saveLocal(student.load(TARGET, STUDENT), scene(3.0))
        val latestStudent = student.studentSnapshot(TARGET)
        val beforeAck = teacher.receiveStudentSnapshot(TARGET, latestStudent)
        assertNotNull(beforeAck.pendingPublish)
        assertEquals(scene(2.0), beforeAck.scene)
        assertEquals(scene(3.0), beforeAck.studentShadow!!.scene)

        val afterAck = teacher.receiveResult(TARGET, delayedAck)
        assertNull(afterAck.pendingPublish)
        assertEquals(scene(3.0), afterAck.scene)
        assertEquals(latestStudent.student, afterAck.commonBase)
        assertEquals(latestStudent.student, afterAck.studentShadow)
        assertFalse(afterAck.draftDirty)
        assertEquals(scene(3.0), teacher.receiveStudentSnapshot(TARGET, latestStudent).scene)
        assertEquals(scene(3.0), teacher.load(TARGET, TEACHER).scene)
    }

    @Test fun `late applied ack preserves newer teacher draft even after a newer student shadow arrived first`() {
        val (student, teacher) = pair()
        val draft = teacher.saveLocal(teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET)), scene(2.0))
        val prepared = teacher.preparePublish(draft)
        teacher.saveLocal(prepared.snapshot, scene(4.0))
        val delayedAck = student.receivePublish(TARGET, prepared.packet!!)
        student.saveLocal(student.load(TARGET, STUDENT), scene(3.0))
        val latestStudent = student.studentSnapshot(TARGET)
        teacher.receiveStudentSnapshot(TARGET, latestStudent)

        val afterAck = teacher.receiveResult(TARGET, delayedAck)
        assertNull(afterAck.pendingPublish)
        assertEquals(scene(4.0), afterAck.scene)
        assertEquals(delayedAck.student, afterAck.commonBase)
        assertEquals(latestStudent.student, afterAck.studentShadow)
        assertTrue(afterAck.draftDirty)
        assertEquals(scene(4.0), teacher.receiveStudentSnapshot(TARGET, latestStudent).scene)
        assertTrue(teacher.preparePublish(teacher.load(TARGET, TEACHER)).conflict)
    }

    private fun pair(): Pair<ConstructionReplicaStore, ConstructionReplicaStore> =
        ConstructionReplicaStore(temporary.newFolder("student")) to ConstructionReplicaStore(temporary.newFolder("teacher"))

    private fun scene(x: Double) = ConstructionScene(points = listOf(GeometryPoint("A", x, 0.0, "A")))

    companion object {
        private val STUDENT = ConstructionReplicaRole.STUDENT
        private val TEACHER = ConstructionReplicaRole.TEACHER
        private val TARGET = ConstructionTarget("local-book", 0, 1, "11111111-1111-4111-8111-111111111111")
    }
}
