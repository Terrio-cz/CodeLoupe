package codeloupe.uiapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Index health per repository, recent builds and files the parser had errors in. */
@Serializable
data class IndexHealth(val repos: List<Repo>, val builds: List<Build>, val errorFiles: List<ErrorFile>, val budgets: Budgets) {
    @Serializable
    data class Repo(
        val id: String,
        val name: String,
        val path: String,
        val baseRef: String,
        val baseCommit: String?,
        val state: RepoIndexState,
        val builtAt: String?,
        val buildMs: Long?,
        val dbBytes: Long,
        val files: Long,
        val decls: Long,
        val refs: Long,
        val errorFiles: Int,
        val layers: Int,
    )

    @Serializable
    enum class BuildKind {
        @SerialName("full") FULL,
        @SerialName("sync") SYNC,
        @SerialName("layer") LAYER,
    }

    @Serializable
    enum class BuildStatus {
        @SerialName("running") RUNNING,
        @SerialName("ok") OK,
        @SerialName("failed") FAILED,
    }

    /** A finished build from the event log (the daemon keeps the last 10 000 events). */
    @Serializable
    data class Build(
        val id: String,
        val repoId: String,
        val kind: BuildKind,
        val startedAt: String,
        val durationMs: Long?,
        val peakRssMb: Long?,
        val files: Int,
        val status: BuildStatus,
        val error: String?,
    )

    /** [firstLine] is 0: the index stores how many errors a file has, not where. */
    @Serializable
    data class ErrorFile(val repoId: String, val path: String, val errors: Int, val firstLine: Int)

    @Serializable
    data class Budgets(val buildPeakRssMb: Long, val daemonRssMb: Long)
}
