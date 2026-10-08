package codeloupe.uiapi

/** The `range` values of the API (docs/ui-spec.md § 9.1) and the days each covers. */
internal object Ranges {
    private val DAYS = linkedMapOf("24h" to 1L, "7d" to 7L, "30d" to 30L)

    fun days(range: String): Long = DAYS[range] ?: throw UiApiException.badRequest("range must be one of ${DAYS.keys.joinToString()}")
}
