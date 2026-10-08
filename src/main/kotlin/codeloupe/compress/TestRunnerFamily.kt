package codeloupe.compress

import codeloupe.jobs.SummaryReader
import java.io.StringReader

/** Test runners other than Gradle (node:test, Jest, pytest, Maven): the counts, the first failures and the last lines. */
object TestRunnerFamily : Family {
    private val COMMAND = Regex("""(^|[\s/\\])(pytest|jest|vitest|mvn|mvnw|node\s+--test|npm\s+(run\s+)?test|go\s+test|dotnet\s+test)\b""")
    private const val TAIL = 5

    override fun matches(line: String) = COMMAND.containsMatchIn(line)

    override fun compress(text: String, cwd: String): String {
        val summary = SummaryReader.read(StringReader(text))
        if (summary.tests == null && summary.failures.isEmpty()) error("no test counts")
        return buildList {
            summary.tests?.let { add("tests $it, passed ${summary.passed}, failed ${summary.failed}" + (summary.skipped?.takeIf { s -> s > 0 }?.let { s -> ", skipped $s" } ?: "")) }
            if (summary.failures.isNotEmpty()) {
                add("failures:")
                summary.failures.forEach { add("  " + ErrorLines.relative(it, cwd)) }
            }
            add("last lines:")
            summary.tail.takeLast(TAIL).forEach { add("  " + ErrorLines.relative(it, cwd)) }
        }.joinToString("\n")
    }
}
