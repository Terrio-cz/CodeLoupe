package codeloupe.hooks

import codeloupe.TestRepos
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptPathTest {
    private val dir = TestRepos.tmpDir("transcripts")

    @Test
    fun `a plain local jsonl file is a transcript`() {
        val file = Files.writeString(dir.resolve("session.jsonl"), "{}\n")
        assertEquals(file, TranscriptPath.of(file.toString()))
    }

    @Test
    fun `network paths, other files, missing files and odd text are refused without being opened`() {
        val file = Files.writeString(dir.resolve("session.jsonl"), "{}\n")
        val notes = Files.writeString(dir.resolve("notes.txt"), "x")
        Files.createDirectories(dir.resolve("folder.jsonl"))
        for (bad in listOf(
            "\\\\attacker\\share\\x.jsonl", "//attacker/share/x.jsonl", "\\\\?\\C:\\x.jsonl", "//./pipe/x.jsonl",
            notes.toString(), dir.resolve("missing.jsonl").toString(), dir.resolve("folder.jsonl").toString(),
            "relative/x.jsonl", "x.jsonl", "", file.toString() + "\u0000", "a".repeat(5000) + ".jsonl",
        )) {
            assertNull(TranscriptPath.of(bad), bad.take(40))
        }
    }
}
