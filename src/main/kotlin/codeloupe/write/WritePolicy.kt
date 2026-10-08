package codeloupe.write

import codeloupe.config.WriteConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the daemon may write. Everywhere: source files of the indexed languages inside the worktree, never `.git`, secrets and
 * keys, a file with merge-conflict markers. On top: the user's `write` config and the repository's own `.codeloupe.json` `write`,
 * which can only forbid more (`linkedWorktreesOnly`, `deny` globs).
 */
class WritePolicy(private val config: WriteConfig) {
    /** Why [relative] (a path with `/`, under [worktree]) must not be written, or null. [text] is its current content, when it has one. */
    fun refusal(worktree: Path, mainWorktree: Path, relative: String, text: String?): String? {
        if (relative.isBlank() || relative.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(relative) || relative.split('/').any { it == ".." }) {
            return "$relative is not a path inside the worktree"
        }
        val parts = relative.split('/')
        if (parts.any { it == ".git" }) return "$relative is inside .git"
        if (parts.last() == REPOSITORY_CONFIG) return "$REPOSITORY_CONFIG is not edited by this tool"
        val ext = relative.substringAfterLast('.', "").lowercase()
        if (ext !in SOURCE) return "$relative is not a Kotlin or Java source file"
        if (SECRET.containsMatchIn(parts.last())) return "$relative looks like a secret or a key"
        if (text != null && CONFLICT.containsMatchIn(text)) return "$relative holds merge-conflict markers: resolve them first"
        val repository = repositoryRules(mainWorktree)
        if ((config.linkedWorktreesOnly || repository.linkedWorktreesOnly) && sameDirectory(worktree, mainWorktree)) {
            return "writes are allowed in linked worktrees only, and ${worktree.fileName} is the main checkout"
        }
        (config.deny + repository.deny).firstOrNull { Glob.matches(it, relative) }?.let { return "$relative is denied by the write policy ($it)" }
        if (!insideWorktree(worktree, relative)) return "$relative leaves the worktree through a link"
        return null
    }

    // The path resolved through links (a link inside the worktree that points out of it) must stay under the worktree.
    private fun insideWorktree(worktree: Path, relative: String): Boolean {
        val real = runCatching { worktree.toRealPath() }.getOrNull() ?: return false
        var path = worktree.resolve(relative)
        while (!Files.exists(path)) path = path.parent ?: return false
        return runCatching { path.toRealPath().startsWith(real) }.getOrDefault(false)
    }

    private fun sameDirectory(a: Path, b: Path) = runCatching { Files.isSameFile(a, b) }.getOrDefault(a.normalize() == b.normalize())

    private class Rules(val linkedWorktreesOnly: Boolean, val deny: List<String>)

    private fun repositoryRules(mainWorktree: Path): Rules {
        val file = mainWorktree.resolve(REPOSITORY_CONFIG)
        val write = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject["write"] as? JsonObject }.getOrNull() ?: return Rules(false, emptyList())
        return Rules(write["linkedWorktreesOnly"]?.toString() == "true", WriteConfig.strings(write["deny"]))
    }

    private companion object {
        const val REPOSITORY_CONFIG = ".codeloupe.json"
        val SOURCE = setOf("kt", "java")
        val SECRET = Regex("""(?i)^(\.env(\..*)?|.*\.(pem|key|p12|pfx|jks|keystore)|id_(rsa|dsa|ecdsa|ed25519).*)$""")
        val CONFLICT = Regex("""(?m)^(<{7}( .*)?|={7}|>{7}( .*)?)\r?$""")
    }
}
