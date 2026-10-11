package codeloupe.accounts

import codeloupe.metrics.AccountIdentity
import java.nio.file.Path

/**
 * The account of a session. First the Claude Desktop instance that started it ([desktop], see [DesktopSessions]), when one lists it.
 * Otherwise from where its transcript lies ([location], see [TranscriptAccounts]) and the fingerprint of the login that ran it
 * ([identity]). An account that names the fingerprint in `accounts.json` gets the session. Else the location stands only
 * when it is trustworthy: an implicit account, and any account on the default `~/.claude` directory, are not, because several logins
 * share that directory. The session then belongs to the anonymous account `identity-<fingerprint>`, which stays separate from every
 * other login and is never mistaken for an undecided one. Without an identity the location is all there is.
 */
class AccountResolver(accounts: List<ClaudeAccount>, userHome: Path = Path.of(System.getProperty("user.home")), val desktop: Map<String, String> = emptyMap()) {
    private val declared: Map<String, String> = accounts.mapNotNull { a -> a.identity?.let { it to a.id } }.groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value.single() }
    private val shared: Set<String> = userHome.resolve(".claude").toAbsolutePath().normalize().let { dir ->
        accounts.filter { it.implicit || it.configDir.toAbsolutePath().normalize() == dir }.mapTo(HashSet()) { it.id }
    }
    private val key = accounts.joinToString("|") { "${it.id}:${it.identity}:${it.implicit}:${it.configDir}" }

    fun resolve(location: String?, identity: String?, session: String? = null): String? {
        session?.let { desktop[it] }?.let { return it }
        if (identity == null) return location
        declared[identity]?.let { return it }
        if (location == null || location in shared || location.startsWith(AccountIdentity.ANONYMOUS_PREFIX)) return AccountIdentity.anonymousAccount(identity)
        return location
    }

    /** Changes when the accounts do, so a stored attribution is looked at again only then. */
    fun signature(): String = key
}
