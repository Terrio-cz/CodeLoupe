package codeloupe.uiapi

import codeloupe.config.Config
import codeloupe.daemon.QueueSnapshot
import codeloupe.events.EventBus
import codeloupe.repo.Registry
import codeloupe.secrets.SecretAccess
import codeloupe.tracker.Trackers
import codeloupe.workspace.WorkspaceState
import codeloupe.workspace.Workspaces
import kotlinx.coroutines.CoroutineScope

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
    secrets: SecretAccess,
) {
    private val catalog = RepoCatalog(registry, workspaces)
    private val callLog = CallLog(config.home.resolve("calls.jsonl"))
    private val worktrees = WorktreeViews(registry, catalog, callLog, scope)
    private val tasks = TaskViews(trackers, worktrees)
    private val index = IndexViews(registry, catalog, events, queue, config.budgets.rssMb)
    private val overview = OverviewViews(callLog, worktrees)
    private val settings = SettingsViews(config, catalog, trackers, trackerPollSec)
    private val environment = EnvironmentViews(secrets, config.secrets.rotationDays)
    private val gaps = GapViews(config.home.resolve(GapViews.FILE))

    suspend fun nav(): Nav = Nav(
        activeWorktrees = catalog.scan().repos.sumOf { r -> r.workspaces.count { it.state == WorkspaceState.ACTIVE && it.role == "worktree" } },
        openTasks = tasks.openCount(), newGaps = 0, indexState = index.overall(),
    )

    suspend fun overview(range: String?): Overview = overview.overview(range ?: "7d")

    suspend fun worktrees(repo: String?, layer: String?, q: String?): WorktreeList = worktrees.list(repo, layer, q)

    suspend fun worktree(id: String): WorktreeDetail = worktrees.detail(id).let { d -> d.copy(task = d.taskId?.let { tasks.summaryOf(it) }) }

    suspend fun tasks(project: String?, state: String?, q: String?, limit: String?, cursor: String?): TaskPage = tasks.page(project, state, q, limit, cursor)

    suspend fun task(id: String): TaskDetail = tasks.detail(id)

    suspend fun index(): IndexHealth = index.health()

    fun gaps(): Gaps = gaps.gaps()

    fun environment(): EnvironmentView = environment.keys()

    fun environmentAudit(name: String?, scope: String?, limit: String?): EnvironmentAuditView {
        val max = limit?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_AUDIT } ?: throw UiApiException.badRequest("limit must be 1..$MAX_AUDIT") } ?: DEFAULT_AUDIT
        return environment.audit(name, scope, max)
    }

    suspend fun settings(): SettingsView = settings.settings()

    fun events(since: String?, limit: String?): EventsView {
        val from = since?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw UiApiException.badRequest("since must be an event number") }
        val max = limit?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_EVENTS } ?: throw UiApiException.badRequest("limit must be 1..$MAX_EVENTS") } ?: DEFAULT_EVENTS
        return index.events(from, max)
    }

    private companion object {
        const val DEFAULT_EVENTS = 100
        const val MAX_EVENTS = 1_000
        const val DEFAULT_AUDIT = 100
        const val MAX_AUDIT = 500
    }
}
