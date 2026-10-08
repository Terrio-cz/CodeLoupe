package codeloupe.docker

/**
 * Who a Docker resource belongs to, written as labels on every resource created through CodeLoupe:
 * the repository (its main worktree's name), the workspace (the worktree directory) and the task of that workspace
 * (empty for a workspace without one, such as the main worktree).
 */
data class Ownership(val repo: String, val workspace: String, val task: String = "") {
    fun labels(): Map<String, String> = mapOf(REPO to repo, WORKSPACE to workspace, TASK to task)

    companion object {
        const val REPO = "codeloupe.repo"
        const val WORKSPACE = "codeloupe.workspace"
        const val TASK = "codeloupe.task"

        /** The ownership written in [labels], null when they do not name a workspace. */
        fun of(labels: Map<String, String>): Ownership? {
            val workspace = labels[WORKSPACE]?.takeIf { it.isNotBlank() } ?: return null
            return Ownership(labels[REPO].orEmpty(), workspace, labels[TASK].orEmpty())
        }
    }
}
