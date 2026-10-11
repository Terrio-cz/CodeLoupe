package codeloupe.uiapi

import kotlinx.serialization.Serializable

/**
 * The Brain screen's list: every workspace with a brain and its topic index (`brain.mjs index`). A workspace whose CLI failed
 * is still listed, with [Workspace.error] and no topics, so one broken brain does not hide the others.
 */
@Serializable
data class BrainView(val workspaces: List<Workspace>) {
    @Serializable
    data class Workspace(
        val id: String,
        val name: String,
        val root: String,
        val topics: List<Topic>,
        val entries: Int,
        val bytes: Long,
        /** `node_missing`, `timeout` or `failed` when the index could not be read. */
        val errorCode: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class Topic(val topic: String, val entries: Int, val bytes: Long, val about: String)
}
