package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** The task files of one workspace brain, newest last entry first: the Brain screen's Tasks view. */
@Serializable
data class BrainTasksView(val workspaceId: String, val tasks: List<Task>) {
    @Serializable
    data class Task(
        val id: String,
        val lastAt: String?,
        val state: List<TaskBrain.StateKey>,
        val entries: Int,
        /** The tracker mirror holds the task, so the task detail opens. */
        val tracked: Boolean,
    )
}
