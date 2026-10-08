package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** The newest entries of the secret audit: who read or changed which name and when, never a value. */
@Serializable
data class EnvironmentAuditView(val events: List<Event>) {
    @Serializable
    data class Event(val at: String, val name: String, val scope: String, val scopeRef: String?, val action: String, val consumer: String)
}
