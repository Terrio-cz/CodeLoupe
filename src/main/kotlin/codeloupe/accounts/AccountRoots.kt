package codeloupe.accounts

import java.nio.file.Files
import java.nio.file.Path

/**
 * Which account a working directory belongs to: the one whose `projects` folder holds a transcript directory for it, the most
 * recently used one when several do (a directory worked in under two accounts). Null when none has seen it.
 */
class AccountRoots(private val accounts: List<ClaudeAccount>) {
    fun accountOf(root: String): String? {
        val name = ProjectDirName.of(root)
        return accounts.mapNotNull { account -> modified(account.projects.resolve(name))?.let { account.id to it } }.maxByOrNull { it.second }?.first
    }

    private fun modified(dir: Path): Long? = runCatching { if (Files.isDirectory(dir)) Files.getLastModifiedTime(dir).toMillis() else null }.getOrNull()
}
