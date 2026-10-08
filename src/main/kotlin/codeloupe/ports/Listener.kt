package codeloupe.ports

import kotlinx.serialization.Serializable

/** A process listening on a TCP port; [process] is its command line, or its executable when the OS hides the arguments. */
@Serializable
data class Listener(val port: Int, val pid: Long? = null, val process: String? = null)
