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
) {
    val size: Int get() = puts.size + removes.size + tombstones.size + copies.size
}
