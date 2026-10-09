package codeloupe.hooks

/** The program of a command and the command without what runs it. */
internal object ShellCommandWords {
    private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")
    private val PREFIXES = setOf("time", "command", "builtin", "exec", "nohup", "env", "sudo", "stdbuf")
    private val LOOP_WORDS = setOf("do", "then", "else", "!", "{", "}")

    /** `C:\tools\RG.exe` -> `rg`. */
    fun program(word: String?): String =
        word?.replace('\\', '/')?.substringAfterLast('/')?.lowercase()?.removeSuffix(".exe")?.removeSuffix(".cmd").orEmpty()

    /** The command without what runs it: variable assignments, `time`, `timeout 60`, `env`, `sudo`. */
    fun unwrap(words: List<String>): List<String> {
        var rest = words
        while (rest.isNotEmpty()) {
            val first = rest.first()
            rest = when {
                ASSIGNMENT.matches(first) -> rest.drop(1)
                program(first) in PREFIXES -> rest.drop(1)
                program(first) == "timeout" -> rest.drop(1).dropWhile { it.startsWith("-") }.drop(1)
                program(first) == "nice" -> rest.drop(1).let { if (it.firstOrNull() == "-n") it.drop(2) else it.dropWhile { w -> w.startsWith("-") } }
                first in LOOP_WORDS -> rest.drop(1)
                else -> return rest
            }
        }
        return rest
    }
}
