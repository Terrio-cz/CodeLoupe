package codeloupe.daemon

import codeloupe.JsonFormat
import codeloupe.platform.IsoTime
import codeloupe.repo.BusyException
import codeloupe.repo.Registry
import codeloupe.tools.Tool
import codeloupe.tools.ToolArgs
import kotlinx.coroutines.CancellationException
import java.time.Instant
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

    suspend fun run(tool: Tool, args: ToolArgs, via: String): ToolOutcome {
        val started = Instant.now()
        var wasBusy = false
        onCall()
        val outcome = try {
            val root = args.string("root")?.takeIf { it.isNotEmpty() } ?: defaultRoot ?: "".takeUnless { tool.needsRoot }
                ?: throw IllegalArgumentException("pass root: the absolute path of the repository or worktree to answer for")
            ToolOutcome(true, tool.answer(registry, root, args))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BusyException) {
            wasBusy = true
            ToolOutcome(false, "busy: ${e.message}")
        } catch (e: Exception) {
            ToolOutcome(false, "error: ${e.message}")
        }
        total.incrementAndGet()
        if (!outcome.ok) errors.incrementAndGet()
        if (wasBusy) busy.incrementAndGet()
        val record = CallRecord(
            t = IsoTime.of(started), tool = tool.name, via = via, ms = Instant.now().toEpochMilli() - started.toEpochMilli(),
            chars = outcome.text.length, ok = outcome.ok, busy = wasBusy, empty = EMPTY.containsMatchIn(outcome.text),
        )
        calls.append(JsonFormat.json.encodeToString(CallRecord.serializer(), record))
        return outcome
    }

    fun stats() = CallStats(total.get(), errors.get(), busy.get())

    private companion object {
        val EMPTY = Regex("^no (declaration|type|indexed file|issue|tasks|ready tasks)")
    }
}
