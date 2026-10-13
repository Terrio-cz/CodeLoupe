package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.read.WriteReply
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The tick, rewrite, attach and create verbs against the recorded YouTrack: what is sent, what the mirror holds, what is refused. */
class WriteVerbsTest {
    private val fake = RecordedYouTrack()
    private var now = 1_791_400_000_000L
    private val store = MirrorStore(TestRepos.tmpDir("write-verbs").resolve("t.db"))
    private val instance = TrackerInstance("t", "youtrack", "https://t.example", listOf("CL"), TokenSource.Env("UNUSED") { emptyMap() })
    private val mirror = TrackerMirror(instance, YouTrackAdapter(fake), store, freshMs = 30_000, clock = { now })

    init {
        runBlocking { mirror.syncProject("CL", 0) }
        fake.requests.clear()
    }

    private fun tick(id: String, match: String): String {
        var note: String? = null
        val result = runBlocking {
            mirror.write(id, emptyMap(), null) { description -> CriterionEdit.tick(description, match).also { note = "ticked: ${it.text}" }.description }
        }
        return WriteReply.render(result, emptySet(), note)
    }

    @Test
    fun `a tick writes the description with one box changed, answers in one line and the mirror holds it`() {
        val reply = tick("CL-26", "no polling")
        assertEquals("CL-26 ticked: No polling when idle · now In Progress", reply)
        val sent = fake.bodies.single()
        assertTrue(sent.contains("- [x] No polling when idle"), sent)
        val held = store.issue("CL-26")!!.description
        assertEquals(1, held.lines().count { it.startsWith("- [x]") })
        assertEquals(RecordedYouTrack.long(fake.issues.getValue("CL-26"), "updated"), store.issue("CL-26")!!.updated)
    }

    @Test
    fun `a refused tick writes nothing`() {
        assertContains(assertFailsWith<TrackerException> { tick("CL-26", "zzz") }.message!!, "no open criterion contains 'zzz'")
        assertContains(assertFailsWith<TrackerException> { tick("CL-26", "e") }.message!!, "matches")
        assertEquals(emptyList(), fake.bodies)
    }

    @Test
    fun `a tick starts from the tracker's text, not the mirror's`() {
        fake.edit("CL-27", 1_791_450_000_000L) {
            it["description"] = JsonPrimitive("- [ ] Second read of an unchanged issue costs < 100 tokens\n- [ ] A line added meanwhile")
        }
        tick("CL-27", "second read")
        assertContains(store.issue("CL-27")!!.description, "A line added meanwhile")
    }

    @Test
    fun `a rewrite keeps the box and the other lines`() {
        val result = runBlocking {
            mirror.write("CL-26", emptyMap(), null) { CriterionEdit.rewrite(it, "polling", "Idle windows cause no requests").description }
        }
        assertContains(WriteReply.render(result, emptySet(), "criterion now: Idle windows cause no requests"), "criterion now: Idle windows cause no requests")
        assertContains(store.issue("CL-26")!!.description, "- [ ] Idle windows cause no requests")
        assertContains(store.issue("CL-26")!!.description, "- [ ] Credentials never logged or returned")
    }

    @Test
    fun `a file is attached and the mirror lists it without a sync`() {
        val stored = runBlocking { mirror.attach("CL-28", "evidence.md", "evidence".toByteArray()) }
        assertEquals("evidence.md", stored.name)
        assertEquals(8L, stored.size)
        assertEquals(listOf("evidence.md" to "evidence"), fake.uploads.map { it.first to String(it.second) })
        assertEquals(listOf("evidence.md"), store.attachments("CL-28").map { it.name })
        assertTrue(fake.requests.single { it.startsWith("POST") }.startsWith("POST /api/issues/CL-28/attachments?fields="))
    }

    @Test
    fun `a file for an issue the tracker does not know is refused`() {
        assertContains(assertFailsWith<TrackerException> { runBlocking { mirror.attach("CL-999", "x.md", ByteArray(1)) } }.message!!, "404")
    }

    @Test
    fun `an issue is created in the project of the epic, linked, and both ends are in the mirror`() {
        val made = runBlocking { mirror.create("CL-4", "A new task", "## Context\nBody", mapOf("Type" to "Task", "Priority" to "Normal")) }
        assertEquals("A new task", made.summary)
        assertEquals("CL-4", made.parent)
        assertEquals("Task", made.type)
        assertEquals("Normal", made.priority)
        assertEquals("dev1", made.assignee, "the token's user when no assignee is given")
        assertEquals("To do", made.state)
        assertNotNull(store.issue(made.id))
        assertContains(store.issue("CL-4")!!.links.map { it.other }, made.id)
        val create = fake.bodies.first()
        assertContains(create, "\"project\":{\"id\":\"p-CL\"}")
        assertContains(create, "\"summary\":\"A new task\"")
        assertContains(fake.bodies.last(), "subtask of CL-4")
    }

    @Test
    fun `a given assignee and other project fields are used and an unknown field is refused before anything is created`() {
        runBlocking { mirror.create("CL-4", "Other", "", mapOf("assignee" to "dev2", "Subsystem" to "Daemon")) }
        val create = fake.bodies.first()
        assertContains(create, "\"name\":\"Assignee\",\"value\":{\"login\":\"dev2\"}")
        assertContains(create, "\"name\":\"Subsystem\",\"value\":{\"name\":\"Daemon\"}")
        assertEquals(1, Regex("\"name\":\"Assignee\"").findAll(create).count())
        fake.bodies.clear()
        assertEquals("no field 'Nope' on CL-4", assertFailsWith<TrackerException> { runBlocking { mirror.create("CL-4", "x", "", mapOf("Nope" to "x")) } }.message)
        assertEquals(emptyList(), fake.bodies)
    }

    @Test
    fun `an issue that could not be linked is named in the error`() {
        fake.failCommands = true
        val failure = assertFailsWith<TrackerException> { runBlocking { mirror.create("CL-4", "Orphan", "", emptyMap()) } }
        assertTrue(Regex("CL-\\d+ was created but is not under CL-4: YouTrack HTTP 400").containsMatchIn(failure.message!!), failure.message)
    }
}
