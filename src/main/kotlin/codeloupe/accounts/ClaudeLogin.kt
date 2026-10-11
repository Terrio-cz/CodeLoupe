package codeloupe.accounts

import codeloupe.metrics.AccountIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The login Claude Code holds for a config directory, as a fingerprint: the account uuid of `oauthAccount` in that directory's
 * `.claude.json`. The default directory `~/.claude` keeps its file one level up, in `~/.claude.json`; when both exist the more
 * recently written one that names an account counts. The uuid is turned into the fingerprint at once and nothing else of the file
 * (e-mail, organisation) is looked at or kept. A file is parsed again only when its size or time changed.
 */
class ClaudeLogin(private val userHome: Path = Path.of(System.getProperty("user.home"))) {
    private class Seen(val modified: Long, val size: Long, val fingerprint: String?)

    private val cache = HashMap<Path, Seen>()

    @Synchronized
    fun fingerprintOf(configDir: Path): String? {
        val dir = configDir.toAbsolutePath().normalize()
        val files = listOfNotNull(dir.resolve(FILE), if (dir == userHome.resolve(".claude").toAbsolutePath().normalize()) userHome.resolve(FILE) else null)
        return files.mapNotNull(::read).maxByOrNull { it.first }?.second
    }

    private fun read(file: Path): Pair<Long, String>? {
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return null
        val size = runCatching { Files.size(file) }.getOrNull()?.takeIf { it <= MAX_BYTES } ?: return null
        val seen = cache[file]?.takeIf { it.modified == modified && it.size == size } ?: Seen(modified, size, parse(file)).also { cache[file] = it }
        return seen.fingerprint?.let { modified to it }
    }

    private fun parse(file: Path): String? = runCatching {
        val account = (Json.parseToJsonElement(Files.readString(file)) as JsonObject)["oauthAccount"] as? JsonObject
        (account?.get("accountUuid") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let(AccountIdentity::fingerprint)
    }.getOrNull()

    private companion object {
        const val FILE = ".claude.json"
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
