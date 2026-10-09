package codeloupe.daemon

import kotlinx.serialization.Serializable

/**
 * Who may call the daemon, as `/status` shows it (never the token itself). [withoutToken] counts the read-only calls since the daemon started
 * that came without it; it stays 0 once every MCP entry, hook and script sends the token, which is when `api.strict` can be turned on.
 */
@Serializable
data class AuthStatus(val strict: Boolean = false, val withoutToken: Long = 0, val lastWithoutTokenMs: Long? = null)
