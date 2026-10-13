package codeloupe.taskcode

/**
 * Where a `dispatch_plan` request is anchored when its root is a workspace rather than the code: the tracker [projects] the
 * workspace owns (the default pool never reaches into the others) and the repository its code lives in, whose worktrees and
 * landed files the plan reads ([codeRoot], null when the workspace names none).
 */
class DispatchScope(val projects: List<String>, val codeRoot: String?)
