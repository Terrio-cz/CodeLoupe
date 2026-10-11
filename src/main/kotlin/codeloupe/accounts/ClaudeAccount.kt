package codeloupe.accounts

import java.nio.file.Path

/**
 * A Claude Code account as the daemon resolves it; [implicit] when nobody listed it and it is only the default config directory.
 * [tokenSecret] is the name of the secret holding its long-lived token, never the token.
 */
data class ClaudeAccount(val id: String, val label: String, val configDir: Path, val implicit: Boolean, val tokenSecret: String? = null) {
    val projects: Path get() = configDir.resolve("projects")
}
