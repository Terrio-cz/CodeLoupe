package codeloupe.uiapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A task with its description, criteria, links and history, all from the mirror. */
@Serializable
data class TaskDetail(
    val id: String,
    val project: String,
    val summary: String,
    val state: String,
    val priority: String?,
    val type: String?,
    val assignee: String?,
    val updatedAt: String,
    val reads: Int,
    val worktreeIds: List<String>,
    val url: String,
    /** Markdown, as the tracker holds it. */
    val description: String,
    val fields: List<Field>,
    val criteria: List<Criterion>,
    val links: List<Link>,
    val activity: List<Activity>,
    val worktrees: List<WorktreeSummary>,
    val mirror: Mirror,
) {
    @Serializable
    data class Field(val name: String, val value: String)

    @Serializable
    data class Criterion(val text: String, val checked: Boolean)

    @Serializable
    data class Link(val type: String, val id: String, val summary: String)

    @Serializable
    enum class ActivityKind {
        @SerialName("created") CREATED,
        @SerialName("comment") COMMENT,
        @SerialName("field") FIELD,
        @SerialName("state") STATE,
    }

    @Serializable
    data class Activity(val at: String, val author: String, val kind: ActivityKind, val text: String)

    @Serializable
    data class Mirror(val syncedAt: String, val lastReadAt: String?)

    companion object {
        fun of(
            s: TaskSummary, url: String, description: String, fields: List<Field>, criteria: List<Criterion>, links: List<Link>,
            activity: List<Activity>, worktrees: List<WorktreeSummary>, mirror: Mirror,
        ) = TaskDetail(
            s.id, s.project, s.summary, s.state, s.priority, s.type, s.assignee, s.updatedAt, s.reads, s.worktreeIds,
            url, description, fields, criteria, links, activity, worktrees, mirror,
        )
    }
}
