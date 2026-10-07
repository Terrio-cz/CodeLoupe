package codeloupe.jobs

import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads a job log once, line by line: test counts of Gradle, Maven/Surefire, node:test (TAP), Jest and pytest
 * summaries (the last one wins), the first distinct failure lines, and the last lines.
 */
object SummaryReader {
    private const val TAIL = 15
    private const val FAILURES = 10
    private const val WIDTH = 200
    private const val MAX_LINE = 4096

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val GRADLE = Regex("""(\d+) tests? completed, (\d+) failed(?:, (\d+) skipped)?""")
    private val SUREFIRE = Regex("""Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)""")
    private val TAP = Regex("""^# (tests|pass|fail|skipped) (\d+)$""")
    private val JEST = Regex("""^Tests:\s+(?:(\d+) failed, )?(?:(\d+) skipped, )?(?:(\d+) passed, )?(\d+) total""")
    private val PYTEST = Regex("""^=+ (.*\b(?:passed|failed)\b.*) in [\d.]+s""")
    private val PYTEST_PART = Regex("""(\d+) (passed|failed|skipped|error)""")
    private val FAILURE = Regex(
        """(?i)(\bFAILED\b|\bFAIL\b|^e: |^error[:\[]|\berror:|^not ok \d|Exception\b.*:|BUILD FAILED|^\s*✗|^\s*×)""",
    )

    fun read(log: Path): JobSummary {
        if (!Files.exists(log)) return JobSummary()
        val counts = Counts()
        val failures = LinkedHashSet<String>()
        val tail = ArrayDeque<String>()
        // An InputStreamReader, not Files.newBufferedReader: bytes in the console code page read as U+FFFD instead of throwing.
        Files.newInputStream(log).reader(Charsets.UTF_8).buffered().use { reader ->
            while (true) {
                val raw = readLine(reader) ?: break
                val line = ANSI.replace(raw, "").trimEnd()
                if (line.isBlank()) continue
                // A count line ("412 tests completed, 2 failed") is a total, not a failure.
                if (!counts.take(line) && failures.size < FAILURES && FAILURE.containsMatchIn(line)) failures += shorten(line.trim())
                tail.addLast(shorten(line))
                if (tail.size > TAIL) tail.removeFirst()
            }
        }
        return counts.summary(failures.toList(), tail.toList())
    }

    /** The next line, cut at [MAX_LINE] characters: one 50 MB line (a minified bundle) must not fill the daemon's heap. */
    private fun readLine(reader: Reader): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return line.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return line.toString()
            if (line.length < MAX_LINE) line.append(c.toChar())
        }
    }

    private fun shorten(line: String) = if (line.length > WIDTH) line.take(WIDTH - 3) + "..." else line

    private class Counts {
        var tests: Int? = null
        var failed: Int? = null
        var skipped: Int? = null
        var passed: Int? = null
        private val tap = HashMap<String, Int>()

        /** Takes the counts of a summary line; false when [line] is none. */
        fun take(line: String): Boolean {
            GRADLE.find(line)?.let { m ->
                set(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 0)
                return true
            }
            SUREFIRE.find(line)?.let { m ->
                val (run, failures, errors, skip) = m.groupValues.drop(1).map(String::toInt)
                set(run, failures + errors, skip)
                return true
            }
            TAP.find(line)?.let { m ->
                tap[m.groupValues[1]] = m.groupValues[2].toInt()
                if ("tests" in tap) set(tap.getValue("tests"), tap["fail"] ?: 0, tap["skipped"] ?: 0)
                return true
            }
            JEST.find(line)?.let { m ->
                set(m.groupValues[4].toInt(), m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2].toIntOrNull() ?: 0)
                return true
            }
            val pytest = PYTEST.find(line) ?: return false
            val parts = PYTEST_PART.findAll(pytest.groupValues[1]).associate { it.groupValues[2] to it.groupValues[1].toInt() }
            val failedCount = (parts["failed"] ?: 0) + (parts["error"] ?: 0)
            set((parts["passed"] ?: 0) + failedCount + (parts["skipped"] ?: 0), failedCount, parts["skipped"] ?: 0)
            return true
        }

        private fun set(total: Int, failedCount: Int, skippedCount: Int) {
            tests = total
            failed = failedCount
            skipped = skippedCount
            passed = (total - failedCount - skippedCount).coerceAtLeast(0)
        }

        fun summary(failures: List<String>, tail: List<String>) = JobSummary(tests, passed, failed, skipped, failures, tail)
    }
}
