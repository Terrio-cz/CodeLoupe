package codeloupe.config

import java.nio.file.Path

/** A place the variable import looks in, and what a file found there means for the scope it is suggested for. */
data class ImportRoot(val path: Path, val kind: Kind) {
    enum class Kind {
        /** Claude's own files (`~/.claude`, `~/.claude.json`): everything found applies globally. */
        HOME,

        /** A folder of Claude Code workspaces: each child folder is one workspace. */
        WORKSPACES,

        /** A folder of repositories: the nearest folder holding `.git` is the repository. */
        REPOSITORIES,
    }
}
