package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.read.WriteReply
import codeloupe.tracker.youtrack.JdkTransport
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in write check against a real tracker, on one throwaway issue: `CODELOUPE_LIVE_TRACKER=<config.json with trackers>`
 * and `CODELOUPE_LIVE_WRITE_ISSUE=<id of a card made for this>`. Sets State, adds a comment, sets State back, and after each
 * write compares the mirror with a fresh read of the issue. Writes `build/reports/codeloupe/live-write.md`.
 */
@EnabledIfEnvironmentVariable(named = "CODELOUPE_LIVE_WRITE_ISSUE", matches = ".+", disabledReason = "writes to a live YouTrack issue: set CODELOUPE_LIVE_WRITE_ISSUE")
class LiveWriteTest {
    @Test
    fun `writes reach the tracker, answer short and leave the mirror equal to the tracker`() {
        val home = TestRepos.tmpDir("live-write")
        Files.copy(Path.of(System.getenv("CODELOUPE_LIVE_TRACKER")), home.resolve("config.json"))
        val id = System.getenv("CODELOUPE_LIVE_WRITE_ISSUE")
        val instance = TrackerSettingsLoader.load(home).instances.first().let { it.copy(projects = listOf(id.substringBefore('-'))) }
        val adapter = YouTrackAdapter(JdkTransport(instance.url, instance.token))
        val mirror = TrackerMirror(instance, adapter, MirrorStore(home.resolve("live.db")), 30_000)
        val report = StringBuilder("# Live write check ($id)\n\n| step | reply | chars |\n|---|---|---|\n")
        assertEquals(null, runBlocking { mirror.refresh(id) })

        fun step(name: String, fields: Map<String, String>, comment: String?) {
            val reply = WriteReply.render(runBlocking { mirror.write(id, fields, comment) }, fields.keys)
            report.append("| $name | `$reply` | ${reply.length} |\n")
            assertTrue(reply.length <= WriteReply.LIMIT, reply)
            val mirrored = mirror.store.issue(id)!!.copy(comments = mirror.store.comments(id), attachments = mirror.store.attachments(id))
            val fresh = adapter.issue(id)!!
            assertEquals(fresh.state, mirrored.state, "$name: state")
            assertEquals(fresh.updated, mirrored.updated, "$name: updated")
            assertEquals(fresh.comments.map { it.id to it.text }, mirrored.comments.map { it.id to it.text }, "$name: comments")
            assertEquals(fresh.fields, mirrored.fields, "$name: fields")
        }
        step("State In Progress", mapOf("State" to "In Progress"), null)
        step("comment", emptyMap(), "CodeLoupe write check")
        step("State + comment", mapOf("State" to "Won't do"), "closed by the check")

        val out = Path.of("build/reports/codeloupe").also(Files::createDirectories).resolve("live-write.md")
        Files.writeString(out, report.toString())
    }
}
