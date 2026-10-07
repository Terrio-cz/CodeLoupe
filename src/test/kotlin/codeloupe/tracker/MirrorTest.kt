package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.mirror.MirrorSync
import codeloupe.tracker.youtrack.HttpReply
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorTest {
    private val fake = RecordedYouTrack()
    private var now = 1_791_400_000_000L
    private val store = MirrorStore(TestRepos.tmpDir("mirror").resolve("t.db"))
    private val instance = TrackerInstance("t", "youtrack", "https://t.example", listOf("CL"), TokenSource.Env("UNUSED") { emptyMap() })
    private val logs = mutableListOf<String>()
    private val mirror = TrackerMirror(instance, YouTrackAdapter(fake), store, freshMs = 30_000, log = { logs += it }, clock = { now })

    private fun sync() = runBlocking { mirror.syncProject("CL", 0) }

    @Test
    fun `youtrack issues map to planning fields, links, fields and criteria`() {
        sync()
        val issue = store.issue("CL-26")!!
        assertEquals(listOf("In Progress", "Feature", "Major", "dev1"), listOf(issue.state, issue.type, issue.priority, issue.assignee))
        assertEquals(2, issue.priorityRank)
        assertEquals("CL-4", issue.parent)
        assertEquals(listOf("CL-56"), issue.links.filter { it.kind == LinkKind.DEPENDS_ON }.map { it.other })
        assertEquals(6, issue.links.count { it.kind == LinkKind.REQUIRED_FOR && it.verb == "is required for" })
        assertEquals(listOf("Subsystem" to "YouTrack mirror", "Fix versions" to "0.3 Context & YouTrack"), issue.fields.map { it.name to it.value })
        assertEquals(2, store.comments("CL-16").size)
        assertEquals(1, store.attachments("CL-16").size)
        assertTrue(store.changes("CL-56").any { it.field == "State" && it.added == "Done" })
        assertEquals(16, store.state("CL").issues)
        assertEquals(fake.issues.values.maxOf { RecordedYouTrack.long(it, "updated") }, store.state("CL").watermark)
    }

    @Test
    fun `incremental sync fetches only what changed and keeps the earlier version`() {
        sync()
        val before = store.issue("CL-27")!!
        fake.requests.clear()
        now += 60_000
        fake.edit("CL-27", now) { fake.setState(it, "Done"); it["resolved"] = JsonPrimitive(now) }
        sync()
        assertEquals(1, fake.issueRequests(), fake.requests.toString())
        assertEquals("Done", store.issue("CL-27")!!.state)
        assertEquals(before, store.revision("CL-27", before.updated))
        assertEquals(now, store.state("CL").syncedAt)
        assertEquals(now, store.state("CL").watermark)
    }

    @Test
    fun `a sync with no change asks for one page of stamps and no history`() {
        sync()
        fake.requests.clear()
        now += 60_000
        sync()
        assertEquals(1, fake.requests.size, fake.requests.toString())
    }

    @Test
    fun `an issue read on its own does not move the sync watermark past older changes`() {
        sync()
        now += 60_000
        fake.edit("CL-28", now) { fake.setState(it, "Review") }
        now += 20 * 60_000
        fake.edit("CL-29", now) { fake.setState(it, "Done") }
        runBlocking { mirror.refresh("CL-29") }
        assertEquals("Done", store.issue("CL-29")!!.state)
        sync()
        assertEquals("Review", store.issue("CL-28")!!.state)
    }

    @Test
    fun `many changes after an idle spell come in one paged query`() {
        sync()
        now += 60_000
        val ids = listOf("CL-90", "CL-91", "CL-92", "CL-93", "CL-94", "CL-95", "CL-27", "CL-28", "CL-29", "CL-30", "CL-1")
        ids.forEachIndexed { i, id -> fake.edit(id, now + i) { fake.setState(it, "Review") } }
        fake.requests.clear()
        sync()
        assertEquals(0, fake.issueRequests(), fake.requests.toString())
        assertTrue(ids.all { store.issue(it)!!.state == "Review" })
    }

    @Test
    fun `a sync younger than the max age is skipped, an older one runs`() {
        sync()
        val calls = fake.requests.size
        now += 10_000
        runBlocking { mirror.syncProject("CL", 60_000) }
        assertEquals(calls, fake.requests.size)
        now += 60_000
        runBlocking { mirror.syncProject("CL", 60_000) }
        assertTrue(fake.requests.size > calls)
    }

    @Test
    fun `deleted issues leave the mirror on a read and on the periodic id check, live ones stay`() {
        sync()
        fake.issues.remove("CL-30")
        fake.issues.remove("CL-28")
        fake.unlisted += "CL-27"
        now += 60_000
        assertEquals("no issue CL-30 in t", runBlocking { mirror.refresh("CL-30") })
        assertNull(store.issue("CL-30"))
        now += MirrorSync.CHECK_MS
        sync()
        assertNull(store.issue("CL-28"))
        assertNotNull(store.issue("CL-27"), "missing from one listing but alive")
        assertEquals(14, store.state("CL").issues)
    }

    @Test
    fun `a moved issue is stored under its new id`() {
        sync()
        now += 60_000
        val old = fake.issues.remove("CL-27")!!
        fake.issues["CL-200"] = JsonObject(old + mapOf("idReadable" to JsonPrimitive("CL-200"), "updated" to JsonPrimitive(now)))
        fake.moved["CL-27"] = "CL-200"
        assertEquals("CL-27 moved to CL-200", runBlocking { mirror.refresh("CL-27") })
        assertNull(store.issue("CL-27"))
        assertNotNull(store.issue("CL-200"))
    }

    @Test
    fun `a read right after a sync makes no call, a later one asks only for updated`() {
        sync()
        fake.requests.clear()
        runBlocking { mirror.refresh("CL-26") }
        assertEquals(0, fake.requests.size)
        now += 60_000
        runBlocking { mirror.refresh("CL-26") }
        assertEquals(listOf("/api/issues/CL-26?fields=updated"), fake.requests)
    }

    @Test
    fun `history that cannot be read does not stop the sync`() {
        fake.failActivities = true
        sync()
        assertEquals(16, store.state("CL").issues)
        assertNotNull(store.state("CL").syncedAt)
        assertTrue(logs.any { "history of CL not updated: YouTrack HTTP 403 on /api/activities: no access to activities" in it }, logs.toString())
    }

    @Test
    fun `a failed sync is recorded and the mirror keeps answering`() {
        sync()
        val broken = TrackerMirror(instance, YouTrackAdapter { HttpReply(503, "{}") }, store, 30_000, clock = { now + 3_600_000 })
        runBlocking { broken.syncProject("CL", 0) }
        assertEquals("YouTrack HTTP 503 on /api/issues", store.state("CL").error)
        assertEquals(16, store.state("CL").issues)
    }
}
