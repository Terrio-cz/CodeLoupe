package codeloupe.accounts

import java.nio.file.Path

/** A Claude Code account as the daemon resolves it; [implicit] when nobody listed it and it is only the default config directory. */
data class ClaudeAccount(val id: String, val label: String, val configDir: Path, val isDefault: Boolean, val implicit: Boolean) {
    val projects: Path get() = configDir.resolve("projects")
}
