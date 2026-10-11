package codeloupe.accounts

import java.nio.file.Path

/**
 * The account a transcript belongs to, as far as its location says: the one account whose `projects` folder holds the file. Null when
 * no listed account does, or when several do (two accounts given the same config directory): then the location proves nothing and the
 * transcript is left without an account instead of being given a guessed one. It is exact only while each account has a config
 * directory of its own; two logins sharing one directory look the same here.
 */
class TranscriptAccounts(accounts: List<ClaudeAccount>) {
    private val roots = accounts.map { it.id to it.projects.toAbsolutePath().normalize() }

    fun of(transcript: Path): String? {
        val file = transcript.toAbsolutePath().normalize()
        return roots.filter { (_, projects) -> file.startsWith(projects) }.map { it.first }.distinct().singleOrNull()
    }
}
