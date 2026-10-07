package codeloupe.tracker

/** What a link means for planning, independent of how the tracker names it. */
enum class LinkKind {
    /** The issue is a subtask of the other (its epic). */
    PARENT,
    SUBTASK,
    DEPENDS_ON,
    REQUIRED_FOR,
    RELATES,
    DUPLICATES,
    DUPLICATED_BY,
    OTHER,
}
