package codeloupe.uiapi

import codeloupe.accounts.AccountRoots
import codeloupe.accounts.Accounts
import codeloupe.accounts.ClaudeAccount
import codeloupe.accounts.ClaudeProfiles
import codeloupe.daemon.CallRecord
import codeloupe.ingest.AccountUsage
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
        val roots = AccountRoots(claude)
        val recent = calls.after(now.minusSeconds(ACTIVE_WINDOW_S)).filter { instant(it)?.isAfter(now.minusSeconds(ACTIVE_WINDOW_S)) == true }.mapNotNull { it.root }.distinct()
        val windows = recent.mapNotNull { roots.accountOf(it) }.groupingBy { it }.eachCount()
        val since = now.minusSeconds(7 * 86_400).toEpochMilli()
        val baseline = transcripts.baseline.state()
        val loaded = (baseline as? BaselineStore.State.Loaded)?.baseline
        val firstHour = Math.floorDiv(since, RunWriter.HOUR_MS)
        val untilHour = Math.floorDiv(now.toEpochMilli(), RunWriter.HOUR_MS) + 1
        val finishedBefore = now.minusSeconds(ACTIVE_WINDOW_S).toEpochMilli()
        fun saved(a: ClaudeAccount) = loaded?.let { Savings(transcripts.queries.usageRows(firstHour, untilHour, AccountUsage.prefix(a.projects)), it, finishedBefore).from(firstHour).savedPct }
        return AccountsView(
            claude = claude.map { a ->
                val used = transcripts.usage.totals(a.projects, since)
                AccountsView.Claude(
                    id = a.id, label = a.label, email = profiles.email(a), configDir = a.configDir.toString(), isDefault = a.isDefault, implicit = a.implicit,
                    exists = Files.isDirectory(a.configDir), windows = windows[a.id] ?: 0, weighted7d = used.weighted, savedPct7d = saved(a),
                    lastUsedAt = used.lastUsedMs?.let { IsoTime.of(Instant.ofEpochMilli(it)) },
                )
            },
            youtrack = youtrack(), baseline = BaselineInfo.of(baseline),
        )
    }

    /** The account whose transcripts the Overview shows when filtered, or null for an id nobody has. */
    fun transcriptPrefix(id: String): String? = accounts.claude().firstOrNull { it.id == id }?.let { codeloupe.ingest.AccountUsage.prefix(it.projects) }

    /** Account ids the Overview filter offers. */
    fun claudeIds(): List<String> = accounts.claude().map { it.id }

    fun rootsOf(id: String): (String) -> Boolean {
        val roots = AccountRoots(accounts.claude())
        return { root -> roots.accountOf(root) == id }
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

    private fun instant(r: CallRecord) = runCatching { Instant.parse(r.t) }.getOrNull()

    private companion object {
        const val ACTIVE_WINDOW_S = 15 * 60L
    }
}
