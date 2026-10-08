package codeloupe.ingest

/** The orders of the runs list, newest or largest first; every one has an index on [column]. */
enum class RunSort(val param: String, val column: String) {
    START("start", "start_ms"),
    WEIGHTED("weighted", "cost"),
    TURNS("turns", "turns"),
    PEAK("peak", "peak"),
    SHARE("share", "share"),
    DURATION("duration", "duration_s"),
    ;

    companion object {
        fun of(param: String): RunSort? = entries.firstOrNull { it.param == param }
    }
}
