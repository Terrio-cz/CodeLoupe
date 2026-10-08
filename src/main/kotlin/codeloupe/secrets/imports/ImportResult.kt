package codeloupe.secrets.imports

import kotlinx.serialization.Serializable

/** What an import did, per occurrence and in total. Names, places and outcomes only. */
@Serializable
data class ImportResult(
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val items: List<Item>,
    /** The files whose variables were replaced by references, and the backup that can put them back; null when nothing was replaced. */
    val replacedFiles: Int,
    val backupId: String?,
    val notReplaced: List<NotReplaced>,
) {
    enum class Outcome { CREATED, UPDATED, SKIPPED_SAME, SKIPPED_DIFFERS, SKIPPED_CONFLICT }

    @Serializable
    data class Item(val id: String, val name: String, val scope: String, val file: String, val outcome: Outcome, val replaced: Boolean = false)

    @Serializable
    data class NotReplaced(val file: String, val reason: String)
}
