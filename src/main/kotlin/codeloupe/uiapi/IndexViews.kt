package codeloupe.uiapi

import codeloupe.daemon.QueueSnapshot
import codeloupe.events.EventBus
import codeloupe.events.EventTypes
import codeloupe.git.GitObjects
import codeloupe.index.Store
import codeloupe.platform.IsoTime
import codeloupe.repo.Registry
import codeloupe.repo.RepoSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The Index screen: each repository's base index, the recent builds from the event log and the files the parser struggled with. */
internal class IndexViews(
    private val registry: Registry,
    private val catalog: RepoCatalog,
    private val events: EventBus,
    private val queue: () -> QueueSnapshot,
    private val daemonRssBudgetMb: Long,
) {
    private class Counts(val files: Long, val decls: Long, val refs: Long, val errorFiles: List<IndexHealth.ErrorFile>)

    private val counts = ConcurrentHashMap<String, Counts>()

    suspend fun health(): IndexHealth {
        val repos = catalog.repos()
        val summaries = registry.snapshot().associateBy { it.id }
        val building = buildingRepos()
        val rows = ArrayList<IndexHealth.Repo>()
        val errorFiles = ArrayList<IndexHealth.ErrorFile>()
        for (ref in repos) {
            val s = summaries[ref.id] ?: continue
            val state = registry.repo(s.commonDir)
            val file = synchronized(state) { state.baseFile }
            val c = file?.let { countsOf(ref.id, s.baseCommit, it) }
            c?.let { errorFiles += it.errorFiles }
            rows += IndexHealth.Repo(
                id = ref.id, name = ref.name, path = ref.path, baseRef = s.defaultRef, baseCommit = s.baseCommit, state = stateOf(s, ref.id in building),
                builtAt = s.lastBuild?.at, buildMs = s.lastBuild?.ms, dbBytes = file?.let { runCatching { Files.size(it) }.getOrDefault(0) } ?: 0,
                files = c?.files ?: 0, decls = c?.decls ?: 0, refs = c?.refs ?: 0, errorFiles = s.lastBuild?.errors ?: 0, layers = s.overlays,
            )
        }
        return IndexHealth(rows, builds(), errorFiles, IndexHealth.Budgets(BUILD_PEAK_BUDGET_MB, daemonRssBudgetMb))
    }

    /** The state a sidebar badge shows: the worst of the repositories. */
    suspend fun overall(): RepoIndexState {
        val building = buildingRepos()
        val states = catalog.repos().mapNotNull { r -> registry.snapshot().firstOrNull { it.id == r.id }?.let { stateOf(it, r.id in building) } }
        return listOf(RepoIndexState.ERROR, RepoIndexState.BUILDING, RepoIndexState.STALE, RepoIndexState.READY).firstOrNull { it in states } ?: RepoIndexState.NONE
    }

    /** Notifications from the event log: finished and failed builds. `since` null only reports where the log stands. */
    fun events(since: Long?, limit: Int): EventsView {
        val last = events.lastSeq()
        if (since == null) return EventsView(events.epoch(), last, emptyList())
        val items = events.since(since, limit).mapNotNull { e ->
            if (e.type != EventTypes.BUILD_DONE) return@mapNotNull null
            val repo = e.data["repo"]?.jsonPrimitive?.contentOrNull
            val name = repo?.let { id -> registry.snapshot().firstOrNull { it.id == id }?.let { registry.mainWorktree(it.commonDir).fileName.toString() } } ?: repo.orEmpty()
            if (e.data["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                EventsView.Item(e.seq, e.at, EventsView.Kind.BUILD_FINISHED, EventsView.Severity.INFO, "Index built", "$name: ${e.data.int("files")} files in ${e.data.long("ms")} ms", EventsView.Ref("index", repo))
            } else {
                EventsView.Item(e.seq, e.at, EventsView.Kind.BUILD_FAILED, EventsView.Severity.WARNING, "Index build failed", "$name: ${e.data["error"]?.jsonPrimitive?.contentOrNull.orEmpty()}", EventsView.Ref("index", repo))
            }
        }
        return EventsView(events.epoch(), last, items)
    }

    private fun stateOf(s: RepoSummary, building: Boolean): RepoIndexState = when {
        building -> RepoIndexState.BUILDING
        s.baseCommit == null -> if (s.failure != null) RepoIndexState.ERROR else RepoIndexState.NONE
        // The old base keeps answering while a newer commit fails to index.
        s.failure != null -> RepoIndexState.STALE
        GitObjects.resolve(s.commonDir, s.defaultRef).let { it != null && it != s.baseCommit } -> RepoIndexState.STALE
        else -> RepoIndexState.READY
    }

    private fun buildingRepos(): Set<String> {
        val q = queue()
        return (listOfNotNull(q.fast.running, q.heavy.running) + q.fast.waiting + q.heavy.waiting)
            .filter { it.startsWith("build:") || it.startsWith("sync:") }.mapTo(HashSet()) { it.split(':').getOrElse(1) { "" } }
    }

    private fun countsOf(repoId: String, commit: String?, file: Path): Counts = counts.compute("$repoId|$commit") { _, known ->
        known ?: Store.open(file, readOnly = true).use { db ->
            Counts(
                Store.count(db, "SELECT count(*) FROM files WHERE deleted = 0"), Store.count(db, "SELECT count(*) FROM decls"), Store.count(db, "SELECT count(*) FROM refs"),
                db.createStatement().use { st ->
                    st.executeQuery("SELECT path, errors FROM files WHERE errors > 0 AND deleted = 0 ORDER BY errors DESC LIMIT $MAX_ERROR_FILES").use { r ->
                        buildList { while (r.next()) add(IndexHealth.ErrorFile(repoId, r.getString(1), r.getInt(2), 0)) }
                    }
                },
            )
        }
    }!!

    private fun builds(): List<IndexHealth.Build> {
        val from = (events.lastSeq() - RECENT_EVENTS).coerceAtLeast(0)
        return events.since(from, RECENT_EVENTS.toInt()).mapNotNull { e ->
            val d = e.data
            val ms = d.long("ms")
            val started = runCatching { IsoTime.of(Instant.parse(e.at).minusMillis(ms ?: 0)) }.getOrDefault(e.at)
            when (e.type) {
                EventTypes.BUILD_DONE -> {
                    val ok = d["ok"]?.jsonPrimitive?.booleanOrNull == true
                    IndexHealth.Build(
                        "${e.seq}", d["repo"]?.jsonPrimitive?.contentOrNull.orEmpty(), IndexHealth.BuildKind.FULL, started, ms, d.long("peakRssMb"), d.int("files") ?: 0,
                        if (ok) IndexHealth.BuildStatus.OK else IndexHealth.BuildStatus.FAILED, d["error"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                EventTypes.OVERLAY_REFRESHED -> IndexHealth.Build(
                    "${e.seq}", d["repo"]?.jsonPrimitive?.contentOrNull.orEmpty(), IndexHealth.BuildKind.LAYER, started, ms, null, d.int("parsed") ?: 0, IndexHealth.BuildStatus.OK, null,
                )
                else -> null
            }
        }.reversed().take(MAX_BUILDS)
    }

    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull

    companion object {
        /** The build worker's resident-memory budget (docs/plan.md § 2). */
        const val BUILD_PEAK_BUDGET_MB = 600L
        private const val RECENT_EVENTS = 500L
        private const val MAX_BUILDS = 50
        private const val MAX_ERROR_FILES = 100
    }
}
