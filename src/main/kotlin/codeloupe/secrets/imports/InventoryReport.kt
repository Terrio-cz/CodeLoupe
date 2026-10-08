package codeloupe.secrets.imports

import kotlinx.serialization.Serializable

/**
 * What a scan found, as names, places and comparisons. Two occurrences share a [Source.hash] only when their values are
 * equal; the hash is salted per report and says nothing else. No field holds a value.
 */
@Serializable
data class InventoryReport(
    val roots: List<String>,
    /** Folders left out as belonging to an excluded system; listed so the user can include them. */
    val excluded: List<String>,
    val counts: Counts,
    val variables: List<Variable>,
) {
    @Serializable
    data class Counts(
        val files: Int,
        val occurrences: Int,
        val names: Int,
        val groups: Int,
        val sensitive: Int,
        /** Groups where two or more sources hold the same value. */
        val duplicates: Int,
        /** Groups where the sources hold different values for the same name and scope. */
        val conflicts: Int,
        val inStore: Int,
        val unreadable: Int,
        val invalidNames: Int,
        val empty: Int,
        val references: Int,
        val excludedFolders: Int,
    )

    /** One name in one suggested scope, with every source that holds it. */
    @Serializable
    data class Variable(
        val name: String,
        val scope: String,
        val sensitive: Boolean,
        /** `new`, `same` (the store holds this value already) or `differs` (the store holds another value). */
        val store: String,
        val duplicate: Boolean,
        val conflict: Boolean,
        val sources: List<Source>,
    )

    @Serializable
    data class Source(val id: String, val file: String, val kind: String, val locator: String, val hash: String)
}
