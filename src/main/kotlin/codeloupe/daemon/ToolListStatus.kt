package codeloupe.daemon

import kotlinx.serialization.Serializable

/**
 * `/status` `toolList`: which MCP tool list this daemon serves. The [fingerprint] covers the version and every tool's name,
 * description and schema, so equal fingerprints mean byte-equal lists and a client's prompt cache of the list stays valid.
 */
@Serializable
data class ToolListStatus(val fingerprint: String = "", val tools: Int = 0, val editOffered: Boolean = false)
