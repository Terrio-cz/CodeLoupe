package codeloupe.tracker

import codeloupe.platform.IsoTime
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.read.IssueReader
import codeloupe.tracker.read.ReadMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.time.Instant

/** Every configured tracker mirror of the daemon, the watcher that keeps them fresh and the per-session read memory. */
class Trackers(
    val mirrors: List<TrackerMirror>,
    private val settings: TrackerSettings,
    private val scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    val reader = IssueReader(ReadMemory(), clock)
    private val watcher = Watcher(scope, settings.syncMs, settings.idleMs, clock) { syncAll(settings.syncMs / 2) }

    val configured: Boolean get() = mirrors.isNotEmpty()

    /** A client is active: keeps (or starts) the watcher. */
    fun touch() {
        if (configured) watcher.touch()
    }

    /** The mirror of [id]'s project with the canonical id, or null when no configured tracker holds that project. */
    fun mirror(id: String): Pair<TrackerMirror, String>? = mirrors.firstNotNullOfOrNull { m -> m.canonical(id)?.let { m to it } }

    fun projects(): List<String> = mirrors.flatMap { it.instance.projects }

    /**
     * Brings stale projects up to date before a query over the whole mirror: waits up to [initialWaitMs] for a first
     * load, [staleWaitMs] for a catch-up after an idle spell. Returns a note on projects that are not current, or null.
     */
    suspend fun current(initialWaitMs: Long, staleWaitMs: Long): String? {
        val firstLoad = mirrors.any { m -> m.instance.projects.any { m.store.state(it).syncedAt == null } }
        val job = scope.launch { syncAll(settings.syncMs) }
        withTimeoutOrNull(if (firstLoad) initialWaitMs else staleWaitMs) { job.join() }
        val notes = mirrors.flatMap { m ->
            m.instance.projects.map { m.store.state(it) }.mapNotNull { s ->
                when {
                    s.error != null -> "${s.project}: last sync failed (${s.error}); mirror of ${Times.short(s.syncedAt)}"
                    s.syncedAt == null -> "${s.project}: first sync running (${s.issues} issues so far)"
                    else -> null
                }
            }
        }
        return notes.takeIf { it.isNotEmpty() }?.joinToString("; ", "(", ")")
    }

    fun summary(): List<TrackerSummary> = mirrors.map { m ->
        TrackerSummary(
            name = m.instance.name,
            url = m.instance.url,
            watching = watcher.running,
            projects = m.instance.projects.map { m.store.state(it) }.map { s ->
                ProjectSummary(s.project, s.issues, s.syncedAt?.let { IsoTime.of(Instant.ofEpochMilli(it)) }, s.error)
            },
        )
    }

    private suspend fun syncAll(maxAgeMs: Long) = mirrors.forEach { it.syncAll(maxAgeMs) }

    override fun close() = mirrors.forEach { it.store.close() }

    companion object {
        /** Mirrors for the configured instances, under `<home>/trackers/<name>.db`; an instance that cannot open is logged and skipped. */
        fun open(settings: TrackerSettings, home: Path, scope: CoroutineScope, log: (String) -> Unit): Trackers {
            settings.problems.forEach { log("config: $it") }
            val mirrors = settings.instances.mapNotNull { instance ->
                val adapter = TrackerAdapters.create(instance) ?: return@mapNotNull null.also { log("tracker ${instance.name}: unknown type ${instance.type}, skipped") }
                runCatching { MirrorStore(home.resolve("trackers").resolve("${safe(instance.name)}.db")) }
                    .onFailure { log("tracker ${instance.name}: mirror not opened (${it::class.simpleName}: ${it.message.orEmpty().lineSequence().first()}), skipped") }
                    .getOrNull()?.let { TrackerMirror(instance, adapter, it, settings.freshMs, log) }
            }
            return Trackers(mirrors, settings, scope)
        }

        private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
