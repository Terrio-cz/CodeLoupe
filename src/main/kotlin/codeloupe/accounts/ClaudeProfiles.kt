package codeloupe.accounts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The e-mail a Claude Code account is signed in with, read from `oauthAccount.emailAddress` of the `.claude.json` that belongs to its
 * config directory (`<configDir>/.claude.json`; for `~/.claude` it is `~/.claude.json`). Nothing else of that file is looked at, and no
 * token is in it. Read again only when the file changes.
 */
class ClaudeProfiles(private val userHome: Path = Path.of(System.getProperty("user.home"))) {
    private data class Stamp(val size: Long, val modified: Long)

    private val cache = HashMap<Path, Pair<Stamp, String?>>()

    @Synchronized
    fun email(account: ClaudeAccount): String? = candidates(account.configDir).firstNotNullOfOrNull(::read)

    private fun candidates(configDir: Path): List<Path> =
        listOfNotNull(configDir.resolve(".claude.json"), userHome.resolve(".claude.json").takeIf { configDir.toAbsolutePath().normalize() == userHome.resolve(".claude").toAbsolutePath().normalize() })

    private fun read(file: Path): String? {
        val stamp = runCatching { Stamp(Files.size(file), Files.getLastModifiedTime(file).toMillis()) }.getOrNull() ?: return null
        cache[file]?.let { if (it.first == stamp) return it.second }
        val email = runCatching {
            val account = (Json.parseToJsonElement(Files.readString(file)) as? JsonObject)?.get("oauthAccount") as? JsonObject
            (account?.get("emailAddress") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { '@' in it }
        }.getOrNull()
        cache[file] = stamp to email
        return email
    }
}
