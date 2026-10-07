package codeloupe.tracker

/** One change of a field in the tracker's history, e.g. State `To do` → `In Progress`. */
data class FieldChange(
    val id: String,
    val issue: String,
    val at: Long,
    val field: String,
    val removed: String,
    val added: String,
    val author: String?,
)
