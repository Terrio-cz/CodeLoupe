package codeloupe.index

import kotlinx.serialization.Serializable

/** A batch of changes to one store, applied in one transaction by [StoreUpdater] (in the daemon or a build worker). */
@Serializable
data class StoreUpdate(
    val puts: List<FilePut> = emptyList(),
    val removes: List<String> = emptyList(),
    val tombstones: List<String> = emptyList(),
    /** Files whose facts are copied from the store [copySource] instead of parsed (parsed when it lacks them). */
    val copies: List<FilePut> = emptyList(),
    val copySource: String? = null,
    val meta: Map<String, String> = emptyMap(),
    /**
     * Stores that may already hold a file of [puts] with the very same content (overlays of other worktrees): its facts are
     * copied from there instead of parsed again. Stores that cannot be read, or are of another format, are skipped.
     */
    val factSources: List<String> = emptyList(),
) {
    val size: Int get() = puts.size + removes.size + tombstones.size + copies.size
}
