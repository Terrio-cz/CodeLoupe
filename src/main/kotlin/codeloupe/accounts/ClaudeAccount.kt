package codeloupe.accounts

import java.nio.file.Path

/**
 * A Claude Code account as the daemon resolves it; [implicit] when nobody listed it and it is only the default config directory.
 * [tokenSecret] is the name of the secret holding its long-lived token, never the token. [identity] is the fingerprint of the account uuid that signs in here, when `accounts.json` names it
 * (CL-226): it lets sessions of accounts that share one config directory be told apart. [desktopDir] is the user-data directory of the
 * Claude Desktop instance signed in as this account (CL-231): the sessions listed in its `claude-code-sessions` belong to it. [email] is the login e-mail `accounts.json` names, which wins over the profile of [configDir].
 */
data class ClaudeAccount(
    val id: String,
    val label: String,
    val configDir: Path,
    val implicit: Boolean,
    val tokenSecret: String? = null,
    val identity: String? = null,
    val desktopDir: Path? = null,
    val email: String? = null,
) {
    val projects: Path get() = configDir.resolve("projects")
}
