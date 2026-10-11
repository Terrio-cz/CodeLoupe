package codeloupe.ingest

import kotlinx.serialization.Serializable

/**
 * A `/usage` limit as stored: [at] when the report was written, the meter ([kind], [scope]), [percent] used, when the window ends
 * ([resetsAt]) and [severity]. [session] is the Claude session that ran `/usage`. [account] is the account whose config directory holds that
 * session's transcript, null when that is not decidable; [identity] is the fingerprint of the account uuid the session named itself, null
 * when it never did. Metadata only: nothing of the conversation.
 */
@Serializable
data class LimitSnapshot(
    val at: String,
    val kind: String,
    val group: String?,
    val scope: String?,
    val percent: Double?,
    val resetsAt: String?,
    val severity: String?,
    val active: Boolean?,
    val session: String,
    val account: String?,
    val identity: String?,
)
