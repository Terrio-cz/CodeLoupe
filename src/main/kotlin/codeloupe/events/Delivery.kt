package codeloupe.events

import kotlinx.serialization.Serializable

/** One event on its way to one URL; the delivery log. [webhookId] is null for a job's `webhook:` action. */
@Serializable
data class Delivery(
    val id: String,
    val webhookId: String?,
    val url: String,
    val seq: Long,
    val type: String,
    val state: String = PENDING,
    val attempts: Int = 0,
    val lastStatus: Int? = null,
    val lastError: String? = null,
    val createdAt: String,
    val updatedAt: String = createdAt,
) {
    companion object {
        const val PENDING = "pending"
        const val DELIVERED = "delivered"
        const val FAILED = "failed"
    }
}
