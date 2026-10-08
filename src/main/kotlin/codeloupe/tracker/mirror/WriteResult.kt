package codeloupe.tracker.mirror

import codeloupe.tracker.IssueComment
import codeloupe.tracker.TrackerIssue

/**
 * What a write left behind: the issue before (null when the mirror did not hold it) and as the tracker holds it now,
 * the comment that was added, and why a part of the write failed (the rest was done).
 */
data class WriteResult(val before: TrackerIssue?, val after: TrackerIssue, val comment: IssueComment?, val problem: String?)
