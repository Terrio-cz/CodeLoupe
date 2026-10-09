package codeloupe.docker

/**
 * Who a Docker resource belongs to, written as labels on every resource created through CodeLoupe:
 * the repository (its main worktree's name), the workspace (the worktree directory) and the task of that workspace
 * (empty for a workspace without one, such as the main worktree), and the id of the installation that made it ([InstallId]; empty for a
 * resource made before the id existed). A container counts as ours only with our id: it inherits the labels of its image.
 */
data class Ownership(val repo: String, val workspace: String, val task: String = "", val install: String = "") {
    fun labels(): Map<String, String> = mapOf(REPO to repo, WORKSPACE to workspace, TASK to task) + if (install.isEmpty()) emptyMap() else mapOf(INSTALL to install)

    /** The same repository, workspace and task, whoever made it. */
    fun sameWorkspace(other: Ownership): Boolean = repo == other.repo && workspace == other.workspace && task == other.task

    companion object {
        const val REPO = "codeloupe.repo"
        const val WORKSPACE = "codeloupe.workspace"
        const val TASK = "codeloupe.task"
        const val INSTALL = "codeloupe.install"

        /** The ownership written in [labels], null when they do not name a workspace. */
        fun of(labels: Map<String, String>): Ownership? {
            val workspace = labels[WORKSPACE]?.takeIf { it.isNotBlank() } ?: return null
            return Ownership(labels[REPO].orEmpty(), workspace, labels[TASK].orEmpty(), labels[INSTALL].orEmpty())
        }
    }
}
