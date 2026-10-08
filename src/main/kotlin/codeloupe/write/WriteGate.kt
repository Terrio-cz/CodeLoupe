package codeloupe.write

import codeloupe.config.WriteConfig
import codeloupe.metrics.Run
import codeloupe.platform.IsoTime
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Whether the `edit` tool is offered. `write.mode` `on` and `off` decide it; `auto` (the default) follows the card behind the tool: it
 * is offered only when the gap detector shows coders still read whole files in order to edit them, or rename by hand for lack of an
 * IDE ([ManualEdits]). The verdict comes from the transcripts of the last `windowDays`, is kept in [cacheFile] for [TTL] and is
 * worked out off the request path: until it exists the tool is not offered.
 */
class WriteGate(
    private val config: WriteConfig,
    private val cacheFile: Path,
    private val now: () -> Instant = Instant::now,
    private val runs: (since: Instant) -> Sequence<Run>,
) {
    @Volatile private var cached: GateVerdict? = readCache()

    /** True when the tool is offered right now; never reads a transcript. */
    fun open(): Boolean = when (config.mode) {
        WriteConfig.ON -> true
        WriteConfig.OFF -> false
        else -> cached?.open == true
    }

    /** Whether the cached verdict is missing or too old to go by. */
    fun stale(): Boolean = config.mode == WriteConfig.AUTO && cached?.let { Duration.between(Instant.parse(it.at), now()) > TTL } != false

    /** Reads the transcripts and keeps the verdict; slow, call it from a background thread. */
    fun refresh(): GateVerdict {
        val verdict = evaluate()
        cached = verdict
        runCatching {
            Files.createDirectories(cacheFile.parent)
            Files.writeString(cacheFile, Json.encodeToString(GateVerdict.serializer(), verdict))
        }
        return verdict
    }

    /** The verdict for the last `windowDays` days, whatever `mode` says: what `metrics gaps` prints. */
    fun evaluate(): GateVerdict {
        val rule = config.gate
        val since = now().minus(Duration.ofDays(rule.windowDays.toLong()))
        var count = 0
        var reads = 0
        var renames = 0
        for (run in runs(since)) {
            count++
            reads += ManualEdits.wholeFileReads(run)
            if (ManualEdits.renamedByHand(run)) renames++
        }
        return GateVerdict(IsoTime.now(), rule.windowDays, count, reads, renames, reads >= rule.wholeFileReads || renames >= rule.manualRenames)
    }

    private fun readCache(): GateVerdict? = runCatching { Json.decodeFromString(GateVerdict.serializer(), Files.readString(cacheFile)) }.getOrNull()

    private companion object {
        val TTL: Duration = Duration.ofHours(6)
    }
}
