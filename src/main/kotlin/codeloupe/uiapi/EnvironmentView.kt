package codeloupe.uiapi

import kotlinx.serialization.Serializable

/**
 * What the secret store holds, by name: scope, where it came from, who read it, how old it is — metadata only, never a value.
 * `storeReady` is false while no key protector works (no OS key store and no passphrase), so nothing can be stored yet.
 */
@Serializable
data class EnvironmentView(val keys: List<Key>, val storeReady: Boolean, val rotationDays: Int) {
    @Serializable
    data class Key(
        val name: String,
        /** `global`, `workspace` or `repo`. */
        val scope: String,
        val scopeRef: String?,
        /** `store` (added by hand or in the app) or `file` (imported from [sourceRef]). */
        val source: String,
        val sourceRef: String?,
        /** Who read it, most recent first. */
        val consumers: List<String>,
        val reads: Int,
        val lastUsedAt: String?,
        val createdAt: String,
        /** The last change: rotated, else created. */
        val updatedAt: String,
        val ageDays: Long,
        /** Older than `rotationDays` (0 = reminders off). */
        val rotationDue: Boolean,
    )
}
