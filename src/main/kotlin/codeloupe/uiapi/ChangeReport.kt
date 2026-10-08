package codeloupe.uiapi

/** What a worktree changed, as the detail view lists it. */
internal data class ChangeReport(
    val baseRef: String,
    val mergeBase: String,
    val changes: List<WorktreeDetail.Change>,
    val callers: List<WorktreeDetail.Caller>,
    val tests: List<WorktreeDetail.TestRef>,
    val changedFiles: Int,
)
