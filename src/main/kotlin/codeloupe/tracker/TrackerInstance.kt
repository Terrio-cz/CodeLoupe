package codeloupe.tracker

/**
 * One configured tracker: its URL, the projects to mirror (short names) and the token source. [repos] are git
 * repositories whose worktree branch names (`TER-5`) mark tasks as taken.
 */
data class TrackerInstance(
    val name: String,
    val type: String,
    val url: String,
    val projects: List<String>,
    val token: TokenSource,
    val repos: List<String> = emptyList(),
)
