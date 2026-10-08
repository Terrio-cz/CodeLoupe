package codeloupe.triage

/** The declaration pointers of a failed build or test run, added to a command's summary without making it longer than [GROWTH] allows. */
object Triage {
    private const val GROWTH = 1.15

    fun apply(summary: String, locator: DeclLocator): String {
        val lines = summary.lines()
        val triaged = TestTriage.apply(ErrorTriage.apply(lines, locator), locator).joinToString("\n")
        return if (triaged.length <= summary.length * GROWTH) triaged else summary
    }
}
