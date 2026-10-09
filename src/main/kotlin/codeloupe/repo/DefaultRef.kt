package codeloupe.repo

import codeloupe.git.GitObjects
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** The branch a repository's base index follows. */
internal object DefaultRef {
    /** `.codeloupe.json` `baseBranch` in the main worktree, else `origin/HEAD`, else main/master. */
    fun of(commonDir: String): String {
        val mainWorktree = if (commonDir.endsWith("/.git")) commonDir.removeSuffix("/.git") else commonDir
        configured(Path.of(mainWorktree, ".codeloupe.json"))?.let { return it }
        GitObjects.symbolic(commonDir, "refs/remotes/origin/HEAD")?.let { return it.removePrefix("refs/remotes/") }
        return CANDIDATES.firstOrNull { GitObjects.resolve(commonDir, it) != null } ?: "HEAD"
    }

    private fun configured(file: Path): String? = runCatching {
        Json.parseToJsonElement(Files.readString(file)).jsonObject["baseBranch"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && !it.startsWith("-") && it.none(Char::isISOControl) }
    }.getOrNull()

    private val CANDIDATES = listOf("origin/main", "origin/master", "main", "master")
}
