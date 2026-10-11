package codeloupe.accounts

import java.nio.file.Path

/**
 * A Claude Code account as the daemon resolves it; [implicit] when nobody listed it and it is only the default config directory.
 * [tokenSecret] is the name of the secret holding its long-lived token, never the token. [identity] is the fingerprint of the account uuid that signs in here, when `accounts.json` names it
 * (CL-226): it lets sessions of accounts that share one config directory be told apart.
 */
data class ClaudeAccount(val id: String, val label: String, val configDir: Path, val implicit: Boolean, val tokenSecret: String? = null, val identity: String? = null) {
    val projects: Path get() = configDir.resolve("projects")
}
