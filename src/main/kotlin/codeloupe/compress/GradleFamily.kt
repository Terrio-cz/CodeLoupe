package codeloupe.compress

/**
 * Gradle: the task list collapses to counts, compiler errors (`e:`), failed tests with their first-party frames, the
 * `What went wrong` block, a few warnings and the build line stay; framework stack frames and Gradle's chatter go.
 */
object GradleFamily : Family {
    private val COMMAND = Regex("""(^|[\s;&|/\\])gradlew?(\.bat)?(\s|$)""")
    private val TASK = Regex("""^> Task (\S+)(?: (.*))?$""")
    private val FAILED_TEST = Regex("""^\S.* > .* FAILED$""")
    private val TEST_TOTAL = Regex("""^\d+ tests? completed, \d+ failed.*$""")
    private val FRAME = Regex("""^\s+at (.+)$""")
    private val FRAMEWORK = Regex("""^(app//)?(org\.junit|org\.gradle|worker\.org\.gradle|java\.base|jdk\.internal|kotlin\.|kotlinx\.coroutines|org\.opentest4j|org\.apache|jdk\.proxy)""")
    private val CHATTER = Regex("""^(WARNING: |Consider enabling configuration cache|Deprecated Gradle features|You can use '--warning-mode|For more on this|See https://docs\.gradle|To honour the JVM|Starting a Gradle Daemon|Daemon will be stopped|\d+ actionable tasks?|Download http|\* Get more help|\* Try:|> Run with|> Task :.* (NO-SOURCE|SKIPPED)$)""")
    private const val WARNINGS = 3
    private const val FRAMES = 3
    private const val WENT_WRONG = 10
    private const val ERRORS = 25

    override fun matches(line: String) = COMMAND.containsMatchIn(line)

    override fun compress(text: String, cwd: String): String {
        val lines = text.lines().map { it.trimEnd() }
        var tasks = 0
        var cached = 0
        val failedTasks = ArrayList<String>()
        for (l in lines) TASK.find(l)?.let { m ->
            tasks++
            val state = m.groupValues[2]
            if (state.contains("UP-TO-DATE") || state.contains("FROM-CACHE") || state.contains("NO-SOURCE") || state.contains("SKIPPED")) cached++
            if (state.contains("FAILED")) failedTasks += m.groupValues[1]
        }
        val errors = lines.filter { it.startsWith("e: ") }.map { ErrorLines.shorten(ErrorLines.relative(it, cwd), 220) }
        val warnings = lines.filter { it.startsWith("w: ") }
        val build = lines.lastOrNull { it.startsWith("BUILD ") }
        val out = ArrayList<String>()
        out += (build ?: "no BUILD line") + " · $tasks tasks" + if (cached > 0) " ($cached up to date or skipped)" else ""
        if (failedTasks.isNotEmpty()) out += "failed tasks: ${failedTasks.joinToString(", ")}"
        lines.lastOrNull { TEST_TOTAL.matches(it) }?.let { out += it }
        out += failedTests(lines, cwd)
        if (errors.isNotEmpty()) {
            out += "compiler errors ${errors.size}:"
            out += errors.take(ERRORS)
            if (errors.size > ERRORS) out += "… +${errors.size - ERRORS} more errors in the full output"
        }
        if (warnings.isNotEmpty()) out += "warnings ${warnings.size}: " + warnings.take(WARNINGS).joinToString(" | ") { ErrorLines.shorten(ErrorLines.relative(it.removePrefix("w: "), cwd), 120) }
        // With compiler errors listed the block only repeats them in prose.
        wentWrong(lines).take(if (errors.isEmpty()) WENT_WRONG else 2).takeIf { it.isNotEmpty() }?.let { out += "what went wrong:"; out += it }
        // Anything else that looks like an error and is not yet shown: kept, never dropped silently.
        val shown = out.joinToString("\n")
        val other = lines.filterIndexed { i, it -> ErrorLines.isErrorAt(lines, i) && !CHATTER.containsMatchIn(it) && !it.startsWith("e: ") && !FAILED_TEST.matches(it) && !it.startsWith("BUILD ") && !it.startsWith("> Task ") && !it.startsWith("FAILURE:") }
            .map { ErrorLines.shorten(ErrorLines.relative(it, cwd)) }.distinct().filter { it !in shown }
        if (other.isNotEmpty()) out += other.take(ERRORS) + listOfNotNull(other.size.takeIf { it > ERRORS }?.let { "… +${it - ERRORS} more error lines in the full output" })
        return out.joinToString("\n")
    }

    /** Each failed test: its line, the exception line under it and up to [FRAMES] frames of our own code. */
    private fun failedTests(lines: List<String>, cwd: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            if (!FAILED_TEST.matches(lines[i])) {
                i++
                continue
            }
            out += lines[i].removeSuffix(" FAILED").let { "FAILED $it" }
            var j = i + 1
            var frames = 0
            var first = true
            while (j < lines.size && (lines[j].startsWith(" ") || lines[j].startsWith("\t"))) {
                val frame = FRAME.find(lines[j])
                when {
                    frame == null && first -> out += "  " + ErrorLines.shorten(ErrorLines.relative(lines[j].trim(), cwd), 240)
                    frame != null && !FRAMEWORK.containsMatchIn(frame.groupValues[1]) && frames < FRAMES -> {
                        out += "  at " + frame.groupValues[1]
                        frames++
                    }
                }
                if (frame == null) first = false
                j++
            }
            i = j
        }
        return out
    }

    private fun wentWrong(lines: List<String>): List<String> {
        val at = lines.indexOfFirst { it.startsWith("* What went wrong") }
        if (at < 0) return emptyList()
        return lines.drop(at + 1).takeWhile { !it.startsWith("* Try") && !it.startsWith("BUILD ") }.filter { it.isNotBlank() }.take(WENT_WRONG).map { ErrorLines.shorten(ErrorLines.relative(it, "")) }
    }
}
