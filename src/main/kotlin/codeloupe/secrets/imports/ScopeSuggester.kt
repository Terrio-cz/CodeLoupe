package codeloupe.secrets.imports

import codeloupe.config.ImportRoot
import codeloupe.secrets.SecretScope
import java.nio.file.Files
import java.nio.file.Path

/** The scope a file's variables are suggested for, from the root it was found under. */
object ScopeSuggester {
    fun suggest(root: ImportRoot, file: Path): SecretScope {
        if (root.kind == ImportRoot.Kind.HOME) return SecretScope.GLOBAL
        val inside = root.path.relativize(file.parent ?: file)
        val first = inside.takeIf { it.toString().isNotEmpty() }?.getName(0)
        return when (root.kind) {
            ImportRoot.Kind.WORKSPACES -> SecretScope.workspace(first?.let(root.path::resolve)?.toString() ?: root.path.toString())
            else -> repository(root, file, first)
        }
    }

    /** The nearest folder up from the file that holds `.git`, else the first folder under the root. */
    private fun repository(root: ImportRoot, file: Path, first: Path?): SecretScope {
        var dir = file.parent
        while (dir != null && dir != root.path && dir.startsWith(root.path)) {
            if (Files.exists(dir.resolve(".git"))) return SecretScope.repository(dir.toString())
            dir = dir.parent
        }
        return if (first == null) SecretScope.workspace(root.path.toString()) else SecretScope.repository(root.path.resolve(first).toString())
    }
}
