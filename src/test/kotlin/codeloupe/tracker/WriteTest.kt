package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.mirror.WriteResult
import codeloupe.tracker.read.WriteReply
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteTest {
    private val fake = RecordedYouTrack()
    private var now = 1_791_400_000_000L
    private val store = MirrorStore(TestRepos.tmpDir("write").resolve("t.db"))
    private val instance = TrackerInstance("t", "youtrack", "https://t.example", listOf("CL"), TokenSource.Env("UNUSED") { emptyMap() })
    private val mirror = TrackerMirror(instance, YouTrackAdapter(fake), store, freshMs = 30_000, clock = { now })

    init {
        sync()
        fake.requests.clear()
    }

    private fun sync() = runBlocking { mirror.syncProject("CL", 0) }

    private fun write(id: String, fields: Map<String, String> = emptyMap(), comment: String? = null) = runBlocking { mirror.write(id, fields, comment) }

    private fun reads() = fake.requests.filter { !it.startsWith("POST ") }

    @Test
    fun `a state change answers one short line and the mirror holds it without reading the issue again`() {
        val result = write("CL-28", mapOf("State" to "In Progress"))
        val reply = WriteReply.render(result, listOf("State"))
        assertEquals("CL-28 State: To do→In Progress", reply)
        assertEquals("In Progress", store.issue("CL-28")!!.state)
        assertEquals(listOf("/api/issues/CL-28?fields=customFields(name)"), reads(), "only the field types were looked up")

        now += 60_000
        fake.requests.clear()
        sync()
        assertEquals(0, fake.issueRequests(), "the next sync finds the mirror current: ${fake.requests}")
    }

    @Test
    fun `field types are remembered, values are shaped by type and cleared by blank`() {
        write("CL-28", mapOf("state" to "Done"))
        fake.requests.clear()
        val result = write("CL-28", mapOf("Assignee" to "dev2", "Fix versions" to "0.4, 0.5", "Subsystem" to "Daemon"))
        assertEquals(emptyList(), reads())
        val body = fake.bodies.last()
        assertContains(body, "\"name\":\"Assignee\",\"value\":{\"login\":\"dev2\"}")
        assertContains(body, "\"value\":[{\"name\":\"0.4\"},{\"name\":\"0.5\"}]")
        assertContains(body, "\"name\":\"Subsystem\",\"value\":{\"name\":\"Daemon\"}")
        val reply = WriteReply.render(result, emptySet())
        assertContains(reply, "Assignee: dev1→dev2")
        assertContains(reply, "Subsystem: YouTrack mirror→Daemon")
        assertContains(reply, "Fix versions: 0.3 Context & YouTrack→0.4, 0.5")
        assertTrue(reply.endsWith(" · now Done"), reply)

        val cleared = write("CL-28", mapOf("Assignee" to ""))
        assertContains(fake.bodies.last(), "\"value\":null")
        assertEquals("CL-28 Assignee: dev2→— · now Done", WriteReply.render(cleared, listOf("Assignee")))
        assertNull(store.issue("CL-28")!!.assignee)
    }

    @Test
    fun `summary and description go on the issue itself`() {
        val result = write("CL-27", mapOf("summary" to "Slimmer writes", "description" to "## Scope\nNew"))
        assertContains(fake.bodies.last(), "\"summary\":\"Slimmer writes\"")
        val reply = WriteReply.render(result, listOf("summary", "description"))
        assertContains(reply, "→Slimmer writes")
        assertContains(reply, "description changed (")
        assertEquals("## Scope\nNew", store.issue("CL-27")!!.description)
    }

    @Test
    fun `a comment lands in the mirror and the issue is not read again`() {
        val result = write("CL-28", comment = "Hotovo")
        assertEquals("CL-28 +comment ${result.comment!!.id} · now To do", WriteReply.render(result, emptySet()))
        assertEquals("Hotovo", store.comments("CL-28").last().text)
        assertEquals(RecordedYouTrack.long(fake.issues.getValue("CL-28"), "updated"), store.issue("CL-28")!!.updated)
        assertEquals(emptyList(), reads())

        now += 60_000
        sync()
        assertEquals(0, fake.issueRequests(), fake.requests.toString())
        assertEquals("Hotovo", store.comments("CL-28").last().text)
    }

    @Test
    fun `fields and a comment together are one reply with the comment in the stored issue`() {
        val result = write("CL-28", mapOf("State" to "Done"), "Landed")
        assertEquals(1, store.comments("CL-28").count { it.text == "Landed" })
        assertEquals("Done", store.issue("CL-28")!!.state)
        assertEquals("CL-28 State: To do→Done · +comment ${result.comment!!.id}", WriteReply.render(result, listOf("State")))
        assertEquals(fake.issues.getValue("CL-28")["comments"].toString().split("\"id\"").size - 1, store.comments("CL-28").size)
    }

    @Test
    fun `a comment that fails after the fields went through is reported, not thrown`() {
        fake.failComments = true
        val result = write("CL-28", mapOf("State" to "Done"), "Landed")
        assertEquals("Done", store.issue("CL-28")!!.state)
        assertContains(WriteReply.render(result, listOf("State")), "comment not added: YouTrack HTTP 400 on POST /api/issues/CL-28/comments: no permission to comment")
        assertFailsWith<TrackerException> { write("CL-28", comment = "Alone") }
    }

    @Test
    fun `an unknown field is refused and nothing is written`() {
        val failure = assertFailsWith<TrackerException> { write("CL-28", mapOf("Nope" to "x")) }
        assertEquals("no field 'Nope' on CL-28", failure.message)
        assertEquals(emptyList(), fake.bodies)
        assertEquals("To do", store.issue("CL-28")!!.state)
    }

    @Test
    fun `an issue the mirror does not hold is still written and shown`() {
        store.delete(listOf("CL-27"))
        val result = write("CL-27", mapOf("State" to "Done"), "x")
        assertNotNull(store.issue("CL-27"))
        assertNull(result.before)
        assertTrue(WriteReply.render(result, listOf("State")).startsWith("CL-27 State: —→Done"))
        assertEquals(1, store.comments("CL-27").count { it.text == "x" })
    }

    @Test
    fun `long values and many changes still fit in 300 characters`() {
        val long = "x".repeat(500)
        val before = store.issue("CL-28")!!
        val after = before.copy(summary = long, description = long, state = "Done", fields = List(30) { FieldValue("Field $it", long) })
        val reply = WriteReply.render(WriteResult(before.copy(fields = emptyList()), after, null, null), emptySet())
        assertTrue(reply.length <= WriteReply.LIMIT, "${reply.length}: $reply")
        assertContains(reply, "… +")
        assertTrue(reply.startsWith("CL-28 "))
        assertEquals("CL-28 no change · now To do", WriteReply.render(WriteResult(before, before, null, null), emptySet()))
    }
}
