package codeloupe.platform

/** A kind of work the daemon times; see [Timings]. */
enum class TimedPart {
    /** A whole tool call, from its root to its text. */
    TOOL,

    /** Checking a worktree against its overlay before a query reads it (git, walk, content compares). */
    CHECK,

    /** Writing a worktree overlay that a check found out of date (parse and store). */
    REFRESH,

    /** A git command, from spawn to exit, or one request to a long-running git process. */
    GIT,

    /** Reading git data in-process (refs, objects, merge-bases). */
    JGIT,

    /** Listing the files of a worktree with their stamps. */
    SCAN,

    /** Opening and closing the read view over a base and its overlay. */
    OPEN,

    /** Running and reading SQL statements of a query. */
    SQL,
}
