package codeloupe.uiapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A worktree with what it changed against the merge-base: the summary's fields plus the detail. */
@Serializable
data class WorktreeDetail(
    val id: String,
    val repoId: String,
    val repoName: String,
    val path: String,
    val branch: String?,
    val head: String,
    val isMain: Boolean,
    val taskId: String?,
    val ahead: Int,
    val behind: Int,
    val changedFiles: Int,
    val changedDecls: Int,
    val layer: LayerState,
    val lastActivityAt: String?,
    val queries24h: Int,
    val baseRef: String,
    val mergeBase: String,
    val changes: List<Change>,
    val callers: List<Caller>,
    val tests: List<TestRef>,
    val task: TaskSummary?,
    val index: LayerIndex,
) {
    @Serializable
    enum class ChangeKind {
        @SerialName("added") ADDED,
        @SerialName("body") BODY,
        @SerialName("signature") SIGNATURE,
        @SerialName("removed") REMOVED,
    }

    @Serializable
    data class Change(val change: ChangeKind, val kind: String, val fqn: String, val path: String, val line: Int?, val callers: Int)

    /** A use of a changed declaration; [exact] is false for a candidate (a reference that may mean it). */
    @Serializable
    data class Caller(val fqn: String, val path: String, val line: Int, val calls: String, val exact: Boolean)

    @Serializable
    data class TestRef(val path: String, val fqn: String?, val reason: String)

    /** [layerFiles] are the indexed files the worktree changed, which is what its layer holds. */
    @Serializable
    data class LayerIndex(val layerFiles: Int, val parsedAt: String?, val errorFiles: List<String>)

    companion object {
        fun of(s: WorktreeSummary, baseRef: String, mergeBase: String, changes: List<Change>, callers: List<Caller>, tests: List<TestRef>, task: TaskSummary?, index: LayerIndex) =
            WorktreeDetail(
                s.id, s.repoId, s.repoName, s.path, s.branch, s.head, s.isMain, s.taskId, s.ahead, s.behind, s.changedFiles, changes.size, s.layer,
                s.lastActivityAt, s.queries24h, baseRef, mergeBase, changes, callers, tests, task, index,
            )
    }
}
