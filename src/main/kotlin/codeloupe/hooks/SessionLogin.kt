package codeloupe.hooks

/**
 * What the hook script saw of a session's own login in the session's environment (CL-226), added to the hook JSON by `hook.sh`:
 * [uuid] is the account uuid Claude Desktop hands its sessions (`CLAUDE_CODE_ACCOUNT_UUID`), [account] the id or e-mail of the account a
 * launcher started the session on (`CODELOUPE_ACCOUNT`), [token] true when the session signs in with its own token
 * (`CLAUDE_CODE_OAUTH_TOKEN`), so the login file of its config directory is not its login. The uuid is only fingerprinted, never kept.
 */
data class SessionLogin(val uuid: String? = null, val account: String? = null, val token: Boolean = false) {
    companion object {
        val NONE = SessionLogin()
    }
}
