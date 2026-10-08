package codeloupe.accounts

import java.nio.file.Files
import java.nio.file.Path

/**
 * The accounts of this machine: those listed in `accounts.json`, else the one Claude Code uses by default (`~/.claude`). Read
 * afresh on every call, so what the app saved shows at once.
 */
class Accounts(
    private val home: Path,
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) {
    fun file(): AccountsFile = AccountsFile.read(home)

    fun claude(): List<ClaudeAccount> {
        val listed = file().claude
        if (listed.isEmpty()) return listOf(ClaudeAccount(DEFAULT_ID, DEFAULT_LABEL, userHome.resolve(".claude"), isDefault = true, implicit = true))
        val defaultId = listed.firstOrNull { it.default }?.id ?: listed.first().id
        return listed.map { ClaudeAccount(it.id, it.label, Path.of(it.configDir), it.id == defaultId, implicit = false) }
    }

    fun youtrack(): List<AccountsFile.Youtrack> = file().youtrack

    /**
     * The project directories under the `projects` folder of every listed account. The implicit default account adds none: its
     * `~/.claude/projects` is what the ingest reads anyway when nothing else is configured.
     */
    fun transcriptDirs(): List<Path> = claude().filterNot { it.implicit }.flatMap { account ->
        runCatching { Files.newDirectoryStream(account.projects).use { dirs -> dirs.filter { Files.isDirectory(it) } } }.getOrDefault(emptyList())
    }

    companion object {
        const val DEFAULT_ID = "default"
        private const val DEFAULT_LABEL = "Výchozí účet"
    }
}
