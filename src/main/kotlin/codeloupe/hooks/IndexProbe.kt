package codeloupe.hooks

import codeloupe.index.Store
import java.nio.file.Path

/** Two questions to a base index that need no view: is this file in it, is there any source under this directory. */
internal object IndexProbe {
    fun hasFile(base: Path, relative: String): Boolean = ask(base, "SELECT 1 FROM files WHERE path = ? AND deleted = 0 LIMIT 1", relative)

    /** [directory] relative to the worktree, empty for the root. */
    fun hasSourcesUnder(base: Path, directory: String): Boolean =
        if (directory.isEmpty()) ask(base, "SELECT 1 FROM files WHERE deleted = 0 LIMIT 1", null)
        else ask(base, "SELECT 1 FROM files WHERE deleted = 0 AND path LIKE ? ESCAPE '\\' LIMIT 1", escape(directory.trimEnd('/')) + "/%")

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun ask(base: Path, sql: String, argument: String?): Boolean = Store.open(base, readOnly = true).use { db ->
        db.prepareStatement(sql).use { statement ->
            argument?.let { statement.setString(1, it) }
            statement.executeQuery().use { it.next() }
        }
    }
}
