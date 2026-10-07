package codeloupe.tracker

import kotlinx.serialization.Serializable

/** One tracker line of `/status`: its projects with mirror size and last sync, and whether the watcher runs. */
@Serializable
data class TrackerSummary(val name: String, val url: String, val watching: Boolean, val projects: List<ProjectSummary>)
