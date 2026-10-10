package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** The Claude and YouTrack accounts of this machine: metadata only, never a token. */
@Serializable
data class AccountsView(val claude: List<Claude>, val youtrack: List<Youtrack>, val baseline: BaselineInfo) {
    @Serializable
    data class Claude(
        val id: String,
        val label: String,
        /** The e-mail the account is signed in with, when its `.claude.json` says. */
        val email: String?,
        val configDir: String,
        val isDefault: Boolean,
        /** Nobody listed it: it is the default config directory, shown until the first account is saved. */
        val implicit: Boolean,
        val exists: Boolean,
        /** Distinct working directories of this account that called CodeLoupe in the last 15 minutes. */
        val windows: Int,
        val weighted7d: Long,
        /** Weighted tokens of the last five hours, the length of the plan's session window. */
        val weighted5h: Long,
        /** Percent this account's runs of the last 7 days saved against the baseline; null without a baseline or without a run to compare. */
        val savedPct7d: Double?,
        val lastUsedAt: String?,
        /** The name of the secret that holds the account's long-lived token (`tokenSecret` in `accounts.json`); null when none is set. Never the token. */
        val tokenSecret: String?,
        /** Whether that secret exists in the store; false without a name. */
        val tokenConfigured: Boolean,
        val limit: Limit,
    ) {
        /**
         * What the account's transcripts say about its usage limit: [state] `limited`, `ok` (a limit was hit and has lifted) or `unknown`
         * (none seen), the [kind] of the newest one (`session`, `weekly`, a model, `spend`), when it [resetsAt] and when it was [observedAt].
         */
        @Serializable
        data class Limit(val state: String, val kind: String?, val resetsAt: String?, val observedAt: String?, val source: String)
    }

    @Serializable
    data class Youtrack(
        val id: String,
        val label: String,
        val url: String,
        val projects: List<String>,
        val tokenConfigured: Boolean,
        /** Added in the app (`accounts.json`); false for a tracker of `config.json`, which the app does not edit. */
        val editable: Boolean,
        val mirror: Mirror,
    ) {
        /** `synced`, `syncing`, `error` or `off` (the daemon has no mirror for it, e.g. before its restart). */
        @Serializable
        data class Mirror(val state: String, val syncedAt: String?)
    }
}
