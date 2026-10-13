package codeloupe.brain

import codeloupe.config.WorkspacesConfig
import codeloupe.repo.Registry
import codeloupe.tracker.Trackers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What the daemon does with the brains of the registered workspaces: ranks their entries for a question, anchors `file:line` citations, reports
 * duplicates and dangling anchors, backfills anchors. The brain files and `brain.mjs` stay the truth and the only writer; nothing the engine does on its
 * critical path (claims, locks, the land gate) asks this class.
 */
class BrainService(
    private val config: WorkspacesConfig,
    private val registry: Registry,
    trackers: Trackers,
    private val engine: BrainEngine = BrainCli(),
    private val scope: CoroutineScope? = null,
    /** Environment for the engine when the daemon starts it, so it finds this daemon again (home and port of a daemon that is not the default). */
    val engineEnv: Map<String, String> = emptyMap(),
    private val autoBackfill: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    private val lastBackfill = ConcurrentHashMap<String, Long>()
    private val indexes = BrainIndexes(engine, ::built)
    private val code = BrainCodeContext(registry, trackers)
    private val anchors = BrainAnchors(registry)
    private val backfiller = BrainBackfill(registry, anchors, engine, engineEnv)

    fun roots(): List<BrainRoot> = BrainRoot.all(config)

    fun root(workspace: String): BrainRoot = BrainRoot.at(config, workspace) ?: throw BrainException(BrainException.Kind.FAILED, "no registered workspace with a brain at $workspace (workspaces.brains / workspaces.repos)")

    suspend fun index(root: BrainRoot): BrainIndex = indexes.get(root)

    /** The workspace a tool call is about: the one named, else the one the caller works in, else the only one registered. */
    fun pick(workspace: String?, caller: String?): BrainRoot {
        workspace?.takeIf { it.isNotBlank() }?.let { return root(it) }
        caller?.takeIf { it.isNotBlank() }?.let { c -> BrainRoot.at(config, c)?.let { return it } }
        val all = roots()
        return all.singleOrNull() ?: throw BrainException(BrainException.Kind.FAILED, if (all.isEmpty()) "no workspace with a brain is registered" else "pass workspace: one of ${all.joinToString { it.path.toString().replace('\\', '/') }}")
    }

    /** `brain.mjs` run for a tool call, with the environment that lets it find this daemon again. */
    suspend fun engine(root: BrainRoot, args: List<String>): String = engine.run(root.path, args, engineEnv)

    suspend fun rank(request: BrainRankRequest): BrainRankResult {
        val index = indexes.get(root(request.workspace))
        val context = if (request.files.isEmpty()) CodeContext.NONE else code.of(request.codeRoot ?: index.info.canonicalRepo, request.files)
        return BrainRankResult(sections = BrainRanker(index).rank(request, context), code = context.takeIf { request.files.isNotEmpty() }?.summary())
    }

    /** Where findings pile up for the files a task will touch: a score per file and module with the entries behind it ([BrainRisk]). */
    suspend fun risk(request: RiskRequest, now: Instant = Instant.now()): RiskResult {
        val index = indexes.get(root(request.workspace))
        val at = request.asOf?.let { BrainRisk.instantOf(it) ?: throw IllegalArgumentException("asOf is not a date or date-time: ${request.asOf}") } ?: now
        val files = request.files.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_RISK_FILES)
        require(files.isNotEmpty()) { "pass files: the paths the task will touch" }
        val context = code.of(request.codeRoot ?: index.info.canonicalRepo, files, declarationsOnly = true)
        return BrainRisk(index).score(request.copy(files = files), context, at)
    }

    suspend fun resolve(request: ResolveRequest): ResolveResult {
        val index = indexes.get(root(request.workspace))
        val codeRoot = (request.codeRoot ?: index.info.canonicalRepo)?.takeIf { it.isNotBlank() } ?: return ResolveResult(note = "no code root")
        return try {
            ResolveResult(anchors.resolve(codeRoot, request.refs, request.trusted))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ResolveResult(note = "code index not readable: ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(120)}")
        }
    }

    suspend fun hygiene(workspace: String): HygieneReport {
        val index = indexes.get(root(workspace))
        val duplicates = BrainDuplicates.of(index.snapshot.entries)
        val codeRoot = index.info.canonicalRepo?.takeIf { it.isNotBlank() } ?: return HygieneReport(duplicates, note = "workspace.json has no canonicalRepo: anchors not checked")
        val entries = index.snapshot.entries.filter { it.level == "workspace" && it.id != null && !it.superseded && it.anchors.any { a -> a.startsWith(IndexedEntry.SYM) || a.startsWith(IndexedEntry.FILE) } }
        return try {
            val rootPath = Path.of(registry.locate(codeRoot).worktree)
            val states = registry.query(codeRoot, speculative = false) { view ->
                entries.flatMap { e -> e.anchors.filter { it.startsWith(IndexedEntry.SYM) || it.startsWith(IndexedEntry.FILE) }.map { a -> Triple(e, a, BrainAnchorCheck.check(view, a, rootPath)) } }
            }
            HygieneReport(
                duplicates,
                dangling = states.filter { it.third is AnchorState.Gone }.map { (e, a) -> HygieneReport.Dangling(e.id.orEmpty(), a, e.text) },
                moved = states.mapNotNull { (e, a, s) -> (s as? AnchorState.Moved)?.let { HygieneReport.Moved(e.id.orEmpty(), a, it.to) } },
                checkedAnchors = states.size,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            HygieneReport(duplicates, note = "code index not readable (${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(120)}): anchors not checked")
        }
    }

    suspend fun backfill(workspace: String): BackfillResult {
        val root = root(workspace)
        return backfiller.run(root, indexes.get(root))
    }

    /** The last index built, if any; a note on a symbol must not make the caller wait for an export. */
    fun cached(root: BrainRoot): BrainIndex? = indexes.cached(root)

    /** Builds the index in the background, so the next question finds it ready. */
    fun warm(root: BrainRoot) {
        scope?.launch { runCatching { indexes.get(root) } }
    }

    private fun built(root: BrainRoot, index: BrainIndex) {
        if (!autoBackfill || scope == null) return
        val now = System.currentTimeMillis()
        val previous = lastBackfill[root.id]
        if (previous != null && now - previous < BACKFILL_EVERY_MS) return
        lastBackfill[root.id] = now
        scope.launch {
            try {
                val result = backfiller.run(root, index)
                if (result.anchored + result.moved > 0) log("brain ${root.name}: backfilled ${result.anchored} entries, followed ${result.moved} moved anchors")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("brain ${root.name}: backfill failed: ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)}")
            }
        }
    }

    private companion object {
        const val BACKFILL_EVERY_MS = 15 * 60 * 1000L
        const val MAX_RISK_FILES = 200
    }
}
