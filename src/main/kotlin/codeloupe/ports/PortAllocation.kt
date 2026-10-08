package codeloupe.ports

import kotlinx.serialization.Serializable

/** A port recorded for a workspace under a name (`app`, `postgres`): the same name of the same workspace always gets the same port. */
@Serializable
data class PortAllocation(val port: Int, val repo: String, val workspace: String, val name: String, val at: String)
