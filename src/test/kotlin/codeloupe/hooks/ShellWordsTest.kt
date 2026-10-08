package codeloupe.hooks

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellWordsTest {
    private fun words(line: String) = ShellWords.split(line).map { it.words }

    @Test
    fun `quotes group words and are removed`() {
        assertEquals(listOf(listOf("rg", "class Foo", "src/main")), words("""rg "class Foo" 'src/main'"""))
        assertEquals(listOf(listOf("grep", "a b", "it's")), words("""grep 'a b' "it's""""))
        assertEquals(listOf(listOf("echo", "")), words("""echo "" """))
    }

    @Test
    fun `operators end a command and a pipe marks the next one`() {
        val commands = ShellWords.split("cd src && rg foo | head -5; ls || echo no")
        assertEquals(listOf("cd", "rg", "head", "ls", "echo"), commands.map { it.program })
        assertEquals(listOf(false, false, true, false, false), commands.map { it.piped })
    }

    @Test
    fun `redirections are dropped and mark the command as writing`() {
        assertEquals(listOf(listOf("cat", "a.kt")), words("cat a.kt 2>&1"))
        assertEquals(listOf(listOf("cat", "a.kt")), words("cat a.kt > out.txt"))
        assertEquals(listOf(listOf("cat", "a.kt")), words("cat a.kt >out.txt 2>/dev/null"))
        assertEquals(listOf(listOf("cat", "a.kt")), words("cat a.kt &>/dev/null"))
        assertEquals(true, ShellWords.split("cat a.kt > out.txt").single().writes)
        assertEquals(false, ShellWords.split("cat a.kt 2>&1").single().writes)
        assertEquals(false, ShellWords.split("cat a.kt 2>/dev/null").single().writes)
    }

    @Test
    fun `a quoted angle bracket is a pattern, not a redirection`() {
        assertEquals(listOf(listOf("rg", ">", "src")), words("""rg ">" src"""))
        assertEquals(listOf(listOf("rg", "a>b", "src")), words("""rg 'a>b' src"""))
    }

    @Test
    fun `here-document bodies are not commands`() {
        val line = "python - <<'EOF'\nimport os\ncat Foo.kt\nrg bar\nEOF\nls"
        assertEquals(listOf("python", "-"), ShellWords.split(line).first().words)
        assertEquals(listOf("python", "ls"), ShellWords.split(line).map { it.program })
    }

    @Test
    fun `windows paths keep their backslashes`() {
        assertEquals(listOf(listOf("cat", """C:\Users\me\repo\Foo.kt""")), words("""cat C:\Users\me\repo\Foo.kt"""))
        assertEquals(listOf(listOf("cat", "a b.kt")), words("""cat a\ b.kt"""))
    }

    @Test
    fun `substitutions and comments stay out of the way`() {
        assertEquals(listOf(listOf("cat", "$(git ls-files | head -1)")), words("cat \$(git ls-files | head -1)"))
        assertEquals(listOf(listOf("rg", "foo")), words("rg foo # find it"))
    }
}
