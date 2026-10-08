package codeloupe.uiapi

import codeloupe.config.Config
import codeloupe.daemon.QueueSnapshot
import codeloupe.events.EventBus
import codeloupe.ingest.Transcripts
import codeloupe.repo.Registry
import codeloupe.tracker.Trackers
import codeloupe.workspace.WorkspaceState
import codeloupe.workspace.Workspaces
import kotlinx.coroutines.CoroutineScope
import java.time.Instant

/**
 * The read-only API of the desktop app (`GET /ui-api/v1/<resource>`, docs/ui-spec.md § 9): every answer is read from what the
 * daemon already holds, nothing is written, and a source that does not exist yet answers with empty data.
 */
class UiApi(
    config: Config,
    registry: Registry,
    workspaces: Workspaces,
    trackers: Trackers,
    events: EventBus,
    queue: () -> QueueSnapshot,
    trackerPollSec: Long,
    scope: CoroutineScope,
    log: (String) -> Unit = {},
    waitMs: Long = Transcripts.DEFAULT_WAIT_MS,
) : AutoCloseable {
    private val catalog = RepoCatalog(registry, workspaces)
    private val callLog = CallLog(config.home.resolve("calls.jsonl"))
    private val worktrees = WorktreeViews(registry, catalog, callLog, scope)
    private val tasks = TaskViews(trackers, worktrees)
    private val index = IndexViews(registry, catalog, events, queue, config.budgets.rssMb)
    private val transcripts = Transcripts(config, { type, data -> events.emit(type, data) }, scope, log, waitMs)
    private val runViews = RunViews(transcripts)
    private val gapViews = GapScreen(transcripts, GapViews(config.home.resolve(GapViews.FILE)))
    private val feed = EventFeed(events, registry, transcripts)
    private val overview = OverviewViews(callLog, worktrees, transcripts)
    private val settings = SettingsViews(config, catalog, trackers, trackerPollSec)

    suspend fun nav(gapsSince: String?): Nav {
        val since = gapsSince?.let { runCatching { Instant.parse(it) }.getOrNull() ?: throw UiApiException.badRequest("gapsSince must be an ISO instant") }
        transcripts.fresh()
        return Nav(
            activeWorktrees = catalog.scan().repos.sumOf { r -> r.workspaces.count { it.state == WorkspaceState.ACTIVE && it.role == "worktree" } },
            openTasks = tasks.openCount(), newGaps = since?.let { transcripts.queries.gapCount(it.toEpochMilli()) } ?: 0, indexState = index.overall(),
        )
    }

    suspend fun overview(range: String?): Overview = overview.overview(range ?: "7d")

    suspend fun worktrees(repo: String?, layer: String?, q: String?): WorktreeList = worktrees.list(repo, layer, q)

    suspend fun worktree(id: String): WorktreeDetail = worktrees.detail(id).let { d -> d.copy(task = d.taskId?.let { tasks.summaryOf(it) }) }

    suspend fun tasks(project: String?, state: String?, q: String?, limit: String?, cursor: String?): TaskPage = tasks.page(project, state, q, limit, cursor)

    suspend fun task(id: String): TaskDetail = tasks.detail(id)

    suspend fun index(): IndexHealth = index.health()

    suspend fun runs(range: String?, sort: String?, role: String?, q: String?, limit: String?, cursor: String?): RunPage = runViews.page(range, sort, role, q, limit, cursor)

    suspend fun run(id: String): RunDetail = runViews.detail(id)

    suspend fun steps(id: String, sort: String?, limit: String?, cursor: String?): StepPage = runViews.steps(id, sort, limit, cursor)

    suspend fun gaps(range: String?, tool: String?, reason: String?): Gaps = gapViews.gaps(range, tool, reason)

    fun environment(): EnvironmentView = settings.environment()

    suspend fun settings(): SettingsView = settings.settings()

    suspend fun events(since: String?, limit: String?): EventsView {
        val from = since?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw UiApiException.badRequest("since must be an event number") }
        val max = limit?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_EVENTS } ?: throw UiApiException.badRequest("limit must be 1..$MAX_EVENTS") } ?: DEFAULT_EVENTS
        return feed.view(from, max)
    }

    override fun close() = transcripts.close()

    private companion object {
        const val DEFAULT_EVENTS = 100
        const val MAX_EVENTS = 1_000
    }
}
