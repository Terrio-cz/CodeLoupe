package codeloupe.secrets

import kotlinx.serialization.Serializable

/** What the store says about a secret without decrypting it; no value is ever part of it. */
@Serializable
data class SecretMeta(
    val name: String,
    val scope: String,
    /** Where it came from: `manual`, or the file an import read it from. */
    val source: String,
    val created: String,
    val rotated: String? = null,
    val lastUsed: String? = null,
    /** What fetched it (`env run: docker`, an MCP server's name), the newest few. */
    val usedBy: List<String> = emptyList(),
)
