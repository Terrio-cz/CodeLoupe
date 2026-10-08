package codeloupe.docker

import kotlinx.serialization.Serializable

/** The answer of `GET /resources`. */
@Serializable
data class ResourceReport(
    val generatedAt: String,
    /** The Engine endpoint and version; null when Docker could not be reached. */
    val engine: String? = null,
    /** Resources per ownership class and kind, e.g. `owned/volume`. */
    val counts: Map<String, Int> = emptyMap(),
    val resources: List<ResourceEntry> = emptyList(),
    /** Why a part of the report is missing: Docker unreachable, a repository the registry could not read. */
    val problems: List<String> = emptyList(),
)
