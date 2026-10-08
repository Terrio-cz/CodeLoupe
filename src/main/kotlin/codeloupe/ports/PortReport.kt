package codeloupe.ports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What holds an allocated port now. */
@Serializable
enum class PortState {
    /** Nothing listens on it. */
    @SerialName("free") FREE,

    /** The workspace's own container, or a process that names the workspace, holds it. */
    @SerialName("in-use") IN_USE,

    /** Something that is not the workspace holds it; [PortStatus.usedBy] says what. */
    @SerialName("conflict") CONFLICT,
}

@Serializable
data class PortStatus(val allocation: PortAllocation, val state: PortState, val usedBy: String? = null)

/** A port of the range that is held but belongs to no workspace of the registry. */
@Serializable
data class ForeignPort(val port: Int, val usedBy: String)

/** The answer of `GET /ports`. */
@Serializable
data class PortReport(
    val generatedAt: String,
    /** The configured range, `19000-19999`; null when none is. */
    val range: String? = null,
    val allocations: List<PortStatus> = emptyList(),
    val foreign: List<ForeignPort> = emptyList(),
    val problems: List<String> = emptyList(),
)
