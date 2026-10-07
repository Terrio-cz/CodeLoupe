package codeloupe.repo

import kotlinx.serialization.Serializable

/** `<home>/repos/<id>/repo.json`: what survives a daemon restart. Also the repository line of `/status`. */
@Serializable
data class RepoRecord(
    val id: String,
    val commonDir: String,
    val defaultRef: String? = null,
    val baseCommit: String? = null,
    val baseFile: String? = null,
    val lastBuild: LastBuild? = null,
)
