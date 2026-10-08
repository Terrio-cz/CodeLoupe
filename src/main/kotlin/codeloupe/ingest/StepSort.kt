package codeloupe.ingest

/** The orders of a run's steps: as they happened, or the most expensive first. */
enum class StepSort(val param: String, val order: String) {
    SEQ("seq", "s.seq"),
    WEIGHTED("weighted", "attr DESC, s.seq"),
    CHARS("chars", "s.chars DESC, s.seq"),
    ;

    companion object {
        fun of(param: String): StepSort? = entries.firstOrNull { it.param == param }
    }
}
