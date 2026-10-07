package codeloupe.daemon

import kotlinx.serialization.Serializable

/** `<home>/daemon.json`, present while a daemon runs. */
@Serializable
data class DaemonInfo(val pid: Long, val port: Int, val version: String, val startedAt: String)
