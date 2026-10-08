package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** Names of the environment variables CodeLoupe manages — metadata only, never a value. Empty until the secret store (CL-50). */
@Serializable
data class EnvironmentView(val keys: List<Key>, val storeReady: Boolean) {
    @Serializable
    data class Key(
        val name: String,
        val scope: String,
        val scopeRef: String?,
        val source: String,
        val consumers: List<String>,
        val lastUsedAt: String?,
        val updatedAt: String,
    )
}
