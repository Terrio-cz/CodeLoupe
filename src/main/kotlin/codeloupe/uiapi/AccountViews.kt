package codeloupe.uiapi

import codeloupe.accounts.AccountLimit
import codeloupe.accounts.AccountRoots
import codeloupe.accounts.Accounts
import codeloupe.accounts.ClaudeAccount
import codeloupe.accounts.ClaudeProfiles
import codeloupe.daemon.CallRecord
import codeloupe.ingest.AccountUsage
import codeloupe.ingest.RunScope
import codeloupe.ingest.RunWriter
import codeloupe.ingest.Transcripts
import codeloupe.metrics.BaselineStore
import codeloupe.platform.IsoTime
import codeloupe.secrets.SecretAccess
import codeloupe.tracker.TrackerMirror
import codeloupe.tracker.Trackers
import java.nio.file.Files
import java.time.Instant

/** The Accounts screen: what `accounts.json` lists, what each Claude account used, and whether each YouTrack mirror runs. */
internal class AccountViews(
    private val accounts: Accounts,
    private val transcripts: Transcripts,
    private val trackers: Trackers,
    private val secrets: SecretAccess,
    private val calls: CallLog,
    private val profiles: ClaudeProfiles = ClaudeProfiles(),
) {
    suspend fun accounts(now: Instant = Instant.now()): AccountsView {
        transcripts.fresh()
        val claude = accounts.claude()
        val accountOf = rootAccounts(claude)
        val recent = calls.after(now.minusSeconds(ACTIVE_WINDOW_S)).filter { instant(it)?.isAfter(now.minusSeconds(ACTIVE_WINDOW_S)) == true }.mapNotNull { it.root }.distinct()
        val windows = recent.mapNotNull(accountOf).groupingBy { it }.eachCount()
        val since = now.minusSeconds(7 * 86_400).toEpochMilli()
        val baseline = transcripts.baseline.state()
        val loaded = (baseline as? BaselineStore.State.Loaded)?.baseline
        val firstHour = Math.floorDiv(since, RunWriter.HOUR_MS)
        val untilHour = Math.floorDiv(now.toEpochMilli(), RunWriter.HOUR_MS) + 1
        val finishedBefore = now.minusSeconds(ACTIVE_WINDOW_S).toEpochMilli()
        val stored = runCatching { secrets.store?.list().orEmpty().map { it.name }.toSet() }.getOrDefault(emptySet())
        val sessionSince = now.minusSeconds(SESSION_WINDOW_S).toEpochMilli()
        fun saved(a: ClaudeAccount) = loaded?.let { Savings(transcripts.queries.usageRows(firstHour, untilHour, scope(a, claude)), it, finishedBefore).from(firstHour).savedPct }
        return AccountsView(
            claude = claude.map { a ->
                val used = transcripts.usage.totals(scope(a, claude), since)
                AccountsView.Claude(
                    id = a.id, label = a.label, email = a.email ?: profiles.email(a), configDir = a.configDir.toString(), implicit = a.implicit,
                    exists = Files.isDirectory(a.configDir), windows = windows[a.id] ?: 0, weighted7d = used.weighted,
                    weighted5h = transcripts.usage.totals(scope(a, claude), sessionSince).weighted, savedPct7d = saved(a),
                    lastUsedAt = used.lastUsedMs?.let { IsoTime.of(Instant.ofEpochMilli(it)) },
                    tokenSecret = a.tokenSecret, tokenConfigured = a.tokenSecret != null && a.tokenSecret in stored,
                    limit = limit(AccountLimit.of(transcripts.queries.lastLimit(scope(a, claude)), now)),
                )
            },
            youtrack = youtrack(), baseline = BaselineInfo.of(baseline),
        )
    }

    /** The runs the Overview shows when filtered by this account, or null for an id nobody has. */
    fun scopeOf(id: String): RunScope? = accounts.claude().let { all -> all.firstOrNull { it.id == id }?.let { scope(it, all) } }

    /** Account ids the Overview filter offers. */
    fun claudeIds(): List<String> = accounts.claude().map { it.id }

    fun rootsOf(id: String): (String) -> Boolean {
        val accountOf = rootAccounts(accounts.claude())
        return { root -> accountOf(root) == id }
    }

    /**
     * The runs of an account. Where a desktop instance is configured (CL-231) the config directory proves nothing, as accounts share it:
     * the account stored with each session decides. Without one, the account's own `projects` folder does.
     */
    private fun scope(a: ClaudeAccount, all: List<ClaudeAccount>): RunScope =
        if (all.any { it.desktopDir != null }) RunScope.ofAccount(a.id) else RunScope.underPrefix(AccountUsage.prefix(a.projects))

    /** The account of a working directory: by where its transcripts lie, or by the account stored for them when a desktop instance is configured. */
    private fun rootAccounts(all: List<ClaudeAccount>): (String) -> String? {
        if (all.none { it.desktopDir != null }) return AccountRoots(all)::accountOf
        return { root -> transcripts.queries.accountOfProject(AccountRoots.names(root)) }
    }

    private fun youtrack(): List<AccountsView.Youtrack> {
        val listed = accounts.youtrack()
        val stored = runCatching { secrets.store?.list().orEmpty().map { it.name }.toSet() }.getOrDefault(emptySet())
        val fromAccounts = listed.map { a ->
            val mirror = trackers.mirrors.firstOrNull { it.instance.name == a.id }
            AccountsView.Youtrack(a.id, a.label ?: a.id, a.url, a.projects.map { it.uppercase() }, a.token in stored, editable = true, mirror = mirror(mirror))
        }
        val fromConfig = trackers.mirrors.filter { m -> listed.none { it.id == m.instance.name } }.map { m ->
            AccountsView.Youtrack(m.instance.name, m.instance.name, m.instance.url, m.instance.projects, runCatching { m.instance.token.read().isNotBlank() }.getOrDefault(false), editable = false, mirror = mirror(m))
        }
        return fromAccounts + fromConfig
    }

    private fun mirror(m: TrackerMirror?): AccountsView.Youtrack.Mirror {
        if (m == null) return AccountsView.Youtrack.Mirror("off", null)
        val states = m.instance.projects.map { m.store.state(it) }
        val synced = states.mapNotNull { it.syncedAt }.minOrNull()?.let { IsoTime.of(Instant.ofEpochMilli(it)) }
        val state = when {
            states.any { it.error != null } -> "error"
            states.any { it.syncedAt == null } -> "syncing"
            else -> "synced"
        }
        return AccountsView.Youtrack.Mirror(state, synced)
    }

    private fun limit(l: AccountLimit) = AccountsView.Claude.Limit(
        l.state, l.kind, l.resetsAt?.let(IsoTime::of), l.observedAt?.let(IsoTime::of), l.source,
    )

    private fun instant(r: CallRecord) = runCatching { Instant.parse(r.t) }.getOrNull()

    private companion object {
        const val ACTIVE_WINDOW_S = 15 * 60L
        const val SESSION_WINDOW_S = 5 * 3600L
    }
}
