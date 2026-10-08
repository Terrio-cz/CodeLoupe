package codeloupe.accounts

import codeloupe.JsonFormat
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/**
 * `<home>/accounts.json`, written by the desktop app (the daemon only reads it): the Claude Code accounts of this machine
 * and the YouTrack instances it mirrors. Nothing secret lives here; a YouTrack token is the name [Youtrack.token] in the
 * encrypted store.
 */
@Serializable
data class AccountsFile(val version: Int = 1, val claude: List<Claude> = emptyList(), val youtrack: List<Youtrack> = emptyList()) {
    /** One Claude Code account: its config directory (`CLAUDE_CONFIG_DIR`) and the name shown for it. */
    @Serializable
    data class Claude(val id: String, val label: String, val configDir: String, val default: Boolean = false)

    @Serializable
    data class Youtrack(val id: String, val label: String? = null, val url: String, val projects: List<String> = emptyList(), val token: String)

    companion object {
        const val FILE = "accounts.json"

        /** What the file says, or nothing when it is absent or damaged: a broken file never stops the daemon. */
        fun read(home: Path): AccountsFile {
            val file = home.resolve(FILE)
            if (!Files.isRegularFile(file)) return AccountsFile()
            return runCatching { JsonFormat.json.decodeFromString(serializer(), Files.readString(file)) }.getOrDefault(AccountsFile())
        }
    }
}
