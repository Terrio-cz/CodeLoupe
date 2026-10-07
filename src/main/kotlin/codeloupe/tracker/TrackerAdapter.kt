package codeloupe.tracker

/**
 * Read access to one issue tracker. Blocking calls; the mirror runs them off the request threads. Implementations
 * never put a token into an exception message.
 */
interface TrackerAdapter {
    /** The canonical id of [id] and its project (`ter-5` → `TER-5`, `TER`); null when it is not an issue id. */
    fun canonical(id: String): Pair<String, String>?

    /** Issues of [project], most recently updated first, until one updated before [since] (all of them when null). */
    fun updatedSince(project: String, since: Long?): List<IssueStamp>

    /** The same issues as [updatedSince] in full; one paged query instead of a call per issue. */
    fun changedSince(project: String, since: Long): List<TrackerIssue>

    /** The newest `updated` of [project]; null when it has no issues. */
    fun newest(project: String): Long?

    /** One page of a project's issues in full, in a stable order. */
    fun page(project: String, skip: Int, top: Int): List<TrackerIssue>

    /** One issue in full; null when it no longer exists. */
    fun issue(id: String): TrackerIssue?

    /** Last update of one issue; null when it no longer exists. */
    fun updated(id: String): Long?

    /** Field changes of [project]'s issues at or after [since], oldest first. */
    fun fieldChanges(project: String, since: Long): List<FieldChange>
}
