package codeloupe.daemon

import codeloupe.JsonFormat
import codeloupe.platform.IsoTime
import codeloupe.platform.TimedPart
import codeloupe.platform.Timings
import codeloupe.repo.BusyException
import codeloupe.repo.Registry
import codeloupe.tools.Tool
import codeloupe.tools.ToolArgs
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Runs a tool against the index of its `root` and records the call. */
class ToolRunner(
    private val registry: Registry,
    private val defaultRoot: String?,
    private val calls: AppendLog,
    /** Every call, before it runs: the tracker watcher keeps syncing only while calls arrive. */
    private val onCall: () -> Unit = {},
) {
    private val total = AtomicInteger()
    private val errors = AtomicInteger()
    private val busy = AtomicInteger()
    private val window = CallWindow()
    private val perRoot = ConcurrentHashMap<String, AtomicInteger>()

    suspend fun run(tool: Tool, args: ToolArgs, via: String): ToolOutcome {
        val started = Instant.now()
        var wasBusy = false
        var callRoot: String? = null
        onCall()
        val outcome = try {
            // A tool without a repository keys per-caller state on root: only the caller's own, never the shared default.
            val root = args.string("root")?.takeIf { it.isNotEmpty() } ?: (if (tool.needsRoot) defaultRoot else "")
                ?: throw IllegalArgumentException("pass root: the absolute path of the repository or worktree to answer for")
            // The worktree the root is in, as git spells its path: the UI matches calls to worktrees by it.
            callRoot = root.takeIf { it.isNotEmpty() }?.let { runCatching { registry.locate(it).worktree }.getOrDefault(it) }
            ToolOutcome(true, Timings.measure(TimedPart.TOOL) { tool.answer(registry, root, args) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: BusyException) {
            wasBusy = true
            ToolOutcome(false, "busy: ${e.message}")
        } catch (e: Exception) {
            ToolOutcome(false, "error: ${e.message}")
        }
        total.incrementAndGet()
        callRoot?.let { perRoot.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
        if (!outcome.ok) errors.incrementAndGet()
        if (wasBusy) busy.incrementAndGet()
        val record = CallRecord(
            t = IsoTime.of(started), tool = tool.name, via = via, ms = Instant.now().toEpochMilli() - started.toEpochMilli(),
            chars = outcome.text.length, ok = outcome.ok, busy = wasBusy, empty = EMPTY.containsMatchIn(outcome.text), root = callRoot,
        )
        window.record(record.tool, record.ms, record.chars, record.busy, record.empty)
        calls.append(JsonFormat.json.encodeToString(CallRecord.serializer(), record))
        return outcome
    }

    fun stats() = CallStats(total.get(), errors.get(), busy.get())

    /** Calls made so far on the worktree [root] (as git spells its path); the hooks tell by it whether advice was taken. */
    fun callsOn(root: String): Int = perRoot[root]?.get() ?: 0

    fun latency() = window.snapshot()

    private companion object {
        val EMPTY = Regex("^no (declaration|type|indexed file|issue|tasks|ready tasks)")
    }
}
