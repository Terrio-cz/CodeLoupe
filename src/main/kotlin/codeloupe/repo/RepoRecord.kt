package codeloupe.repo

import kotlinx.serialization.Serializable

/** `<home>/repos/<id>/repo.json`: what survives a daemon restart. */
@Serializable
data class RepoRecord(
    val id: String,
    val commonDir: String,
    val defaultRef: String? = null,
    val baseCommit: String? = null,
    val baseFile: String? = null,
    /** Index format of [baseFile]; a base of another format is rebuilt. */
    val format: String? = null,
    val lastBuild: LastBuild? = null,
)
