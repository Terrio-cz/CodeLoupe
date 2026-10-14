package codeloupe.ci

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The timed slow classes of gradle/test-shards.gradle.kts (CL-195) still exist and are listed once. */
class TestShardsTest {
    private val script = Files.readString(Path.of(System.getProperty("codeloupe.projectDir"), "gradle", "test-shards.gradle.kts"))

    private val slow = (Regex("val slowTests = mapOf\\((.*?)\\n\\)", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)
        ?: error("no `val slowTests = mapOf(` in the script"))
        .let { body -> Regex("\"([\\w.]+)\" to (\\d+)").findAll(body).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList() }

    @Test
    fun `every slow class named in the script exists`() {
        assertTrue(slow.size >= 10, "the list of slow classes is empty or not read: $slow")
        for ((name, _) in slow) Class.forName(name)
    }

    @Test
    fun `a class is listed once and has a time`() {
        assertEquals(slow.size, slow.map { it.first }.toSet().size, "a class is listed twice: $slow")
        assertTrue(slow.all { it.second > 0 }, "$slow")
    }
}
