package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.read.Similar
import codeloupe.tracker.read.SimilarTerms
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimilarTest {
    private val store = MirrorStore(TestRepos.tmpDir("similar").resolve("t.db"))

    init {
        val instance = TrackerInstance("t", "youtrack", "https://t.example", listOf("CL"), TokenSource.Env("UNUSED") { emptyMap() })
        runBlocking { TrackerMirror(instance, YouTrackAdapter(RecordedYouTrack()), store, 30_000).syncProject("CL", 0) }
    }

    // The recorded issues were resolved around the time they were recorded; "now" is a day after the newest of them.
    private val now = store.read { db -> db.createStatement().use { s -> s.executeQuery("SELECT MAX(updated) FROM issues").use { it.next(); it.getLong(1) } } } + 86_400_000

    @Test
    fun `a draft that restates a task finds it first, with the words they share`() {
        val hits = Similar.find(store, "Slim write proxy: field updates and comments return only what changed", nowMs = now)
        assertEquals("CL-28", hits.first().row.id)
        assertTrue(hits.first().shared.containsAll(listOf("slim", "write", "proxy")), hits.first().shared.toString())
        val text = Similar.render(hits)
        assertContains(text, "similar tasks (best first):")
        assertContains(text, "CL-28 To do")
        assertContains(text, "relates to")
    }

    @Test
    fun `the description counts too, and an unrelated draft finds nothing`() {
        val viaDescription = Similar.find(store, "Faster tracker reads", "the local YouTrack mirror with an incremental watcher that polls for changes", nowMs = now)
        assertTrue(viaDescription.any { it.row.id == "CL-26" }, viaDescription.map { it.row.id }.toString())
        val none = Similar.find(store, "Quarterly gardening budget spreadsheet", nowMs = now)
        assertTrue(none.isEmpty())
        assertContains(Similar.render(none), "no similar task")
    }

    @Test
    fun `old resolved tasks are not suggested, a hit needs two shared words, the limit holds`() {
        val farFuture = now + 400L * 86_400_000
        val resolvedRecently = Similar.find(store, "watcher polls incremental mirror", nowMs = now).map { it.row.id }
        val resolvedLongAgo = Similar.find(store, "watcher polls incremental mirror", nowMs = farFuture).map { it.row.id }
        assertTrue(resolvedLongAgo.size <= resolvedRecently.size)
        Similar.find(store, "mirror watcher polls incremental youtrack", nowMs = now).forEach { assertTrue(it.shared.size >= 2, it.shared.toString()) }
        assertTrue(Similar.find(store, "mirror youtrack tasks issue", limit = 2, nowMs = now).size <= 2)
    }

    @Test
    fun `terms drop stop words and numbers, put the summary first and cap at sixteen`() {
        assertEquals(listOf("slim", "write", "proxy"), SimilarTerms.of("Add the slim write proxy", ""))
        assertEquals(listOf("mirror", "watcher"), SimilarTerms.of("Mirror 2026 from the watcher", "mirror 42"))
        assertEquals(listOf("cleanup", "docker", "volumes"), SimilarTerms.of("Docker cleanup", "volumes volumes volumes docker").sorted())
        assertEquals(16, SimilarTerms.of((1..40).joinToString(" ") { "word$it" }, "").size)
        assertTrue(SimilarTerms.of("the and for", "").isEmpty())
        assertEquals(listOf("úklid", "kontejnerů"), SimilarTerms.of("Úklid kontejnerů pro", ""))
    }

    @Test
    fun `an answer comes well under 100 ms from a warm mirror`() {
        Similar.find(store, "mirror watcher polls", nowMs = now)
        val started = System.nanoTime()
        repeat(10) { Similar.find(store, "Local YouTrack mirror with incremental watcher", "polls the tracker for changes since the watermark", nowMs = now) }
        val perCallMs = (System.nanoTime() - started) / 10 / 1_000_000
        assertTrue(perCallMs < 100, "similar took $perCallMs ms")
    }
}
