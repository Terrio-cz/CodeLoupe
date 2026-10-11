package codeloupe.ci

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hand-placed slow classes of gradle/test-shards.gradle.kts (CL-195) still exist, and each has a share. */
class TestShardsTest {
    private val script = Files.readString(Path.of(System.getProperty("codeloupe.projectDir"), "gradle", "test-shards.gradle.kts"))

    private fun listOf(name: String): String =
        Regex("val $name = listOf\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1) ?: error("no `val $name = listOf(` in the script")

    private val slow = Regex("\"([\\w.]+)\"").findAll(listOf("slowTests")).map { it.groupValues[1] }.toList()
    private val shares = Regex("\\d+").findAll(listOf("slowShare")).map { it.value.toInt() }.toList()

    @Test
    fun `every slow class named in the script exists`() {
        assertTrue(slow.size >= 10, "the list of slow classes is empty or not read: $slow")
        for (name in slow) Class.forName(name)
    }

    @Test
    fun `every slow class has a share and a name is listed once`() {
        assertEquals(slow.size, shares.size, "slowTests has ${slow.size} classes, slowShare ${shares.size} shares")
        assertEquals(slow.size, slow.toSet().size, "a class is listed twice: $slow")
        assertTrue(shares.all { it == 0 || it == 1 }, "$shares")
        assertEquals(setOf(0, 1), shares.toSet(), "both shares need a slow class")
    }
}
