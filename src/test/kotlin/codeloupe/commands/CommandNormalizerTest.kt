package codeloupe.commands

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandNormalizerTest {
    @Test
    fun `a write to a path nothing touched before creates the file, a later one overwrites it`() {
        val n = CommandNormalizer()
        assertEquals("Write (new file)", n.of("Write", "C:\\w\\src\\A.kt").label)
        assertEquals("Write (existing file)", n.of("Write", "c:/w/src/a.kt").label, "slashes and case do not tell paths apart")
        assertEquals("Write (new file)", n.of("Write", "src/B.kt").label)
    }

    @Test
    fun `a file read or edited first is an existing file`() {
        val n = CommandNormalizer()
        n.of("Read", "src/A.kt")
        n.of("Edit", "src/B.kt")
        assertEquals("Write (existing file)", n.of("Write", "src/A.kt").label)
        assertEquals("Write (existing file)", n.of("Write", "src/B.kt").label)
    }

    @Test
    fun `a path that is not known leaves the write plain`() {
        val n = CommandNormalizer()
        assertEquals("Write", n.of("Write", "").label)
        assertEquals("Write", n.of("Write", "C:/w/" + "x".repeat(190) + "…").label)
    }

    @Test
    fun `runs do not share what they touched`() {
        CommandNormalizer().of("Read", "src/A.kt")
        assertEquals("Write (new file)", CommandNormalizer().of("Write", "src/A.kt").label)
    }

    @Test
    fun `file tools are grouped by tool name and the other tools by what they are`() {
        val n = CommandNormalizer()
        assertEquals(CommandLabel("Read", "file"), n.of("Read", "src/A.kt"))
        assertEquals(CommandLabel("Edit", "file"), n.of("Edit", "src/A.kt"))
        assertEquals(CommandLabel("Grep", "search"), n.of("Grep", "foo"))
        assertEquals(CommandLabel("Glob", "search"), n.of("Glob", "**/*.kt"))
        assertEquals(CommandLabel("codeloupe/symbol", "mcp"), n.of("mcp__codeloupe__symbol", "Foo"))
        assertEquals(CommandLabel("Agent", "agent"), n.of("Agent", "review it"))
        assertEquals(CommandLabel("TodoWrite", "other"), n.of("TodoWrite", ""))
        assertEquals(CommandLabel("git diff --stat", "shell"), n.of("Bash", "git diff --stat"))
        assertEquals(CommandLabel("heredoc write", "heredoc"), n.of("Bash", "cat > a.txt <<'EOF' hi EOF"))
        assertEquals(CommandLabel("Get-Content <path>", "shell"), n.of("PowerShell", "Get-Content a.txt"))
    }
}
