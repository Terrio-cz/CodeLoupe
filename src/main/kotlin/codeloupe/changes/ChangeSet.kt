package codeloupe.changes

import java.nio.file.Path

/**
 * What a worktree changed against the merge-base with the default branch: indexed [files], other changed paths
 * ([otherFiles], not indexed), and [beforeFile], an index holding the merge-base version of every changed file
 * (null when no file existed before).
 */
data class ChangeSet(
    val worktree: String,
    val defaultRef: String,
    val mergeBase: String,
    val files: List<ChangedFile>,
    val otherFiles: List<String>,
    val beforeFile: Path?,
)
