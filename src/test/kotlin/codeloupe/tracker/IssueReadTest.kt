package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.read.IssueReader
import codeloupe.tracker.read.Parts
import codeloupe.tracker.read.ReadMemory
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueReadTest {
    private val fake = RecordedYouTrack()
    private var now = 1_791_400_000_000L
    private val store = MirrorStore(TestRepos.tmpDir("issue").resolve("t.db"))
    private val instance = TrackerInstance("t", "youtrack", "https://t.example", listOf("CL"), TokenSource.Env("UNUSED") { emptyMap() })
    private val mirror = TrackerMirror(instance, YouTrackAdapter(fake), store, freshMs = 30_000, clock = { now })
    private val reader = IssueReader(ReadMemory()) { now }

    init {
        runBlocking { mirror.syncProject("CL", 0) }
    }

    private fun read(id: String, parts: Parts = Parts.of(null, emptyList()), since: String? = null, session: String = "w1") =
        runBlocking { reader.read(mirror, id, parts, since, session) }

    /** Moves time on and changes an issue in the tracker. */
    private fun change(id: String, block: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit) {
        now += 60_000
        fake.edit(id, now, block)
    }

    @Test
    fun `brief has fields, links, the criteria checklist and a section index, not the prose`() {
        val brief = read("CL-26")
        assertEquals(
            """
            CL-26 Local YouTrack mirror with incremental watcher
            In Progress · Feature · Major · @dev1 · Subsystem: YouTrack mirror · Fix versions: 0.3 Context & YouTrack
            epic CL-4 YouTrack task index & mirror
            depends on CL-56 Done
            is required for CL-90, CL-91, CL-92, CL-93, CL-94, CL-95
            Criteria 0/3:
            [ ] Mirror stays within 5 min of YouTrack while windows run
            [ ] No polling when idle
            [ ] Credentials never logged or returned
            Sections (lines): Context 1 · Scope 1 · Verification 1
            """.trimIndent(),
            brief.lines().dropLast(1).joinToString("\n"),
        )
        assertTrue(brief.lines().last().startsWith("no comments · updated 2026-"), brief)
        assertFalse("SQLite mirror behind" in brief)
    }

    @Test
    fun `a second read of an unchanged issue is one short line, per session`() {
        read("CL-26")
        val again = read("CL-26")
        assertTrue(again.startsWith("CL-26 unchanged since your read at "), again)
        assertTrue(again.endsWith(" (In Progress, criteria 0/3; since=none shows it again)"), again)
        assertTrue(again.length < 120, "${again.length} chars")
        read("CL-26", session = "")
        assertContains(read("CL-26", session = ""), "Criteria 0/3", message = "no session key, no memory")
        assertContains(read("CL-26", session = "w2"), "Criteria 0/3")
        assertContains(read("CL-26", since = "none"), "Criteria 0/3")
        assertContains(read("CL-26", Parts.of("full", emptyList())), "SQLite mirror behind")
    }

    @Test
    fun `after a change the reader gets only the delta`() {
        read("CL-27")
        change("CL-27") {
            fake.setState(it, "In Progress")
            it["description"] = JsonPrimitive(it["description"]!!.jsonPrimitive.content.replaceFirst("- [ ]", "- [x]").replace("## Scope\n", "## Scope\nOne more line.\n"))
            fake.addComment(it, "c1", "dev1", now, "Started on the brief view.")
        }
        val delta = read("CL-27")
        assertTrue(delta.startsWith("CL-27 changed since your read at "), delta)
        assertContains(delta, "State: To do → In Progress")
        assertContains(delta, "[x] Second read of an unchanged issue costs < 100 tokens")
        assertContains(delta, "changed sections: Scope")
        assertContains(delta, "+1 comments (dev1)")
        assertFalse("Started on the brief view." in delta)
        assertTrue(read("CL-27").startsWith("CL-27 unchanged since"))
        val full = read("CL-27", Parts.of("full", emptyList()))
        assertContains(full, "Started on the brief view.")
    }

    @Test
    fun `a full reader sees changed sections and comments in full`() {
        read("CL-27", Parts.of("full", emptyList()))
        change("CL-27") {
            it["description"] = JsonPrimitive(it["description"]!!.jsonPrimitive.content.replace("## Scope\n", "## Scope\nOne more line.\n"))
            fake.addComment(it, "c2", "bot", now, "Evidence attached.")
        }
        val delta = read("CL-27", Parts.of("full", emptyList()))
        assertContains(delta, "## Scope\nOne more line.")
        assertContains(delta, "+ bot ")
        assertContains(delta, "Evidence attached.")
    }

    @Test
    fun `since a given time diffs against the version current then`() {
        val t0 = now
        change("CL-28") { fake.setState(it, "In Progress") }
        val delta = read("CL-28", since = codeloupe.platform.IsoTime.of(java.time.Instant.ofEpochMilli(t0)))
        assertContains(delta, "State: To do → In Progress")
        assertTrue(read("CL-28", since = "2099-01-01").startsWith("CL-28 unchanged since 2099-01-01"))
        assertContains(read("CL-28", since = "2020-01-01"), "the whole issue")
    }

    @Test
    fun `sections show only the asked parts, by heading prefix or pseudo-section`() {
        val parts = read("CL-16", Parts.of(null, listOf("scope", "comments", "history", "nope")))
        assertTrue(parts.startsWith("CL-16 [Done] "), parts)
        assertContains(parts, "## Scope\n")
        assertContains(parts, "## Comments\n")
        assertContains(parts, "## History\n")
        assertContains(parts, "State: ")
        assertContains(parts, "no section 'nope'; have: ")
        assertFalse("## Context" in parts)
        val reread = read("CL-16", Parts.of(null, listOf("scope")))
        assertTrue(reread.startsWith("CL-16 unchanged"), reread)
    }

    @Test
    fun `a removed criterion and a time cutoff are reported as such`() {
        read("CL-27")
        change("CL-27") { it["description"] = JsonPrimitive(it["description"]!!.jsonPrimitive.content.lines().filterNot { l -> "Criteria checklist" in l }.joinToString("\n")) }
        assertContains(read("CL-27"), "removed criterion: Criteria checklist rendered compactly")
        val t0 = now
        change("CL-16") { fake.addComment(it, "c3", "dev1", now, "After t0.") }
        val delta = read("CL-16", Parts.of(null, listOf("comments")), since = codeloupe.platform.IsoTime.of(java.time.Instant.ofEpochMilli(t0)))
        assertContains(delta, "+ dev1 ")
        assertEquals(1, delta.lines().count { it.startsWith("+ ") }, "older comments are not new: $delta")
    }

    @Test
    fun `brief of an epic counts subtasks instead of listing them`() {
        val epic = read("CL-4")
        assertContains(epic, "subtasks 11: 0 resolved")
        assertFalse("CL-95" in epic)
    }

    @Test
    fun `an issue outside the mirror is fetched on read`() {
        store.delete(listOf("CL-29"))
        now += 1
        assertContains(read("CL-29"), "CL-29 Generic YouTrack configuration")
    }
}
