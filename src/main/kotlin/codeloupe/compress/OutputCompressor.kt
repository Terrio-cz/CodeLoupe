package codeloupe.compress

/**
 * Shortens the output of a command for an agent: the families know git status / log / diff, Gradle and test runners,
 * the rest is cut to its head, its error lines and its tail. A short output is returned as it is, and every line that
 * looks like an error survives or is counted with a pointer to the full output, which the caller keeps by handle.
 */
object OutputCompressor {
    const val PASSTHROUGH = 600
    private const val PASSTHROUGH_LINES = 20

    private val FAMILIES: List<Family> = listOf(GitStatusFamily, GitLogFamily, GitDiffFamily, GradleFamily, TestRunnerFamily)

    class Result(val text: String, val family: String, val shortened: Boolean)

    private val SHELLS = setOf("bash", "sh", "zsh", "dash", "cmd", "powershell", "pwsh")
    private val SCRIPT_FLAGS = setOf("-c", "/c", "-command", "-lc")

    /** The command as a family recognises it: the program and its arguments, or a shell's script; a JVM's class path is no command. */
    private fun headline(command: List<String>): String {
        val exe = command.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.lowercase()?.removeSuffix(".exe").orEmpty()
        return when {
            exe in SHELLS -> command.drop(1).dropWhile { it.lowercase() !in SCRIPT_FLAGS }.drop(1).joinToString(" ")
            exe == "java" || exe == "javaw" -> ""
            else -> (listOf(exe) + command.drop(1)).joinToString(" ")
        }
    }

    fun compress(command: List<String>, output: String, cwd: String = ""): Result {
        val text = Ansi.strip(output).replace("\r\n", "\n").trimEnd()
        if (text.length <= PASSTHROUGH && text.lines().size < PASSTHROUGH_LINES) return Result(text, "passthrough", false)
        val line = headline(command)
        val family = FAMILIES.firstOrNull { it.matches(line) }
        val shortened = family?.let { runCatching { it.compress(text, cwd.replace('\\', '/')) }.getOrNull() }?.takeIf { it.isNotBlank() }
            ?: return Result(GenericFamily.compress(text, cwd), "generic", true)
        // A family that did not make it shorter is no family at all.
        return if (shortened.length < text.length) Result(shortened, family.javaClass.simpleName.removeSuffix("Family").lowercase(), true) else Result(GenericFamily.compress(text, cwd), "generic", true)
    }
}
