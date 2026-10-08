package codeloupe.ingest

import codeloupe.JsonFormat
import codeloupe.metrics.Categorizer
import codeloupe.metrics.ParserSnapshot
import codeloupe.metrics.TranscriptParser
import codeloupe.platform.IsoTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.nameWithoutExtension

/**
 * Reads the agents' transcripts into [TranscriptDb], lazily: nothing runs until a UI API call asks ([refresh]), and a call
 * within [ttlMs] of the last pass finds the data as it is. A pass lists the transcripts, skips those whose size and time did not
 * change and reads the others from the offset it stopped at, so a transcript is parsed once however often it grows.
 */
class TranscriptIngest(
    private val dirs: () -> List<Path>,
    private val db: TranscriptDb,
    private val categorizer: Categorizer,
    private val watch: BudgetWatch,
    private val gaps: GapAnnouncer,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val ttlMs: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val writer = RunWriter(db, categorizer)
    private var files: MutableMap<String, FileState>? = null
    private var job: Job? = null
    private val jobLock = Any()
    private val passLock = Any()
    @Volatile
    private var lastPassMs = 0L

    @Volatile
    private var status = IngestStatus(false, 0, 0, null)

    @Volatile
    private var closing = false

    fun status(): IngestStatus = status

    /**
     * Starts a pass unless one runs or the last one is recent, and waits up to [waitMs] for it. A pass that takes longer (the
     * first one over gigabytes) goes on in the background and the caller answers with what is stored.
     */
    suspend fun refresh(waitMs: Long) {
        val running = synchronized(jobLock) {
            job?.takeIf { it.isActive } ?: if (now() - lastPassMs < ttlMs) null else {
                status = IngestStatus(true, 0, 0, status.at)
                scope.launch(Dispatchers.IO) { pass() }.also { job = it }
            }
        } ?: return
        withTimeoutOrNull(waitMs) { running.join() }
    }

    /** Ends a running pass after the file it is reading and waits for it, so the database can be closed. */
    fun close() {
        closing = true
        synchronized(passLock) {}
    }

    /** One pass in the calling thread, for tests. */
    fun passNow() = pass()

    private fun pass() {
        synchronized(passLock) { run() }
    }

    private fun run() {
        try {
            val known = files ?: writer.loadFiles().also { files = it }
            if (db.meta(CATEGORIES) != categorizer.fingerprint) {
                db.clear()
                known.clear()
                db.transaction { db.setMeta(it, CATEGORIES, categorizer.fingerprint) }
            }
            val todo = TranscriptScanner.scan(dirs()).filter { f -> known[f.key].let { it == null || it.size != f.size || it.mtime != f.mtime } }.sortedByDescending { it.mtime }
            status = IngestStatus(true, 0, todo.size, status.at)
            val added = ArrayList<GapRecord>()
            var done = 0
            for (batch in todo.chunked(BATCH_FILES)) {
                if (closing) return
                db.transaction {
                    for (f in batch) {
                        if (closing) break
                        runCatching { added += read(f, known) }.onFailure { log("transcript ${f.path.fileName}: ${it.message}") }
                        status = IngestStatus(true, ++done, todo.size, status.at)
                    }
                }
            }
            val quiet = db.meta(BASELINE) == null
            if (quiet) db.transaction { db.setMeta(it, BASELINE, IsoTime.now()) }
            runCatching { watch.check(quiet) }.onFailure { log("budgets: ${it.message}") }
            if (!quiet) gaps.announce(added)
        } catch (e: Exception) {
            files = null
            log("transcript ingest failed: ${e.message}")
        } finally {
            lastPassMs = now()
            status = IngestStatus(false, status.filesTotal, status.filesTotal, IsoTime.of(Instant.ofEpochMilli(lastPassMs)))
        }
    }

    private fun read(f: FoundTranscript, known: MutableMap<String, FileState>): List<GapRecord> {
        var state = known[f.key]
        if (state != null && f.size < state.offset) {
            writer.forget(f.key)
            known.remove(f.key)
            state = null
        }
        if (state != null && f.size == state.offset) {
            writer.touch(f)
            known[f.key] = FileState(f.size, f.mtime, state.offset, state.ter, state.state)
            return emptyList()
        }
        val snapshot = state?.state?.let { JsonFormat.json.decodeFromString(ParserSnapshot.serializer(), it) }
        val (role, metaTer) = if (state == null) metaOf(f) ?: return emptyList() else (snapshot?.role ?: "main") to state.ter
        val parser = TranscriptParser(role, snapshot)
        val offset = state?.offset ?: 0
        val consumed = Files.newByteChannel(f.path).use { channel ->
            channel.position(offset)
            consume(LineReader(Channels.newInputStream(channel), offset), parser, offset)
        }
        val (results, usages) = parser.drain()
        val ter = metaTer ?: TER.find(parser.firstPrompt)?.value
        val delta = FileDelta(f, ter, parser, results, usages, consumed)
        val added = writer.write(delta)
        known[f.key] = FileState(f.size, f.mtime, consumed, ter, JsonFormat.json.encodeToString(ParserSnapshot.serializer(), parser.snapshot()))
        return added
    }

    /** Feeds the lines to [parser]; the offset after the last line it took. A last line the writer has not finished stays for next time. */
    private fun consume(lines: LineReader, parser: TranscriptParser, from: Long): Long {
        var consumed = from
        while (true) {
            val line = lines.next() ?: break
            val text = line.text
            if (!line.terminated && (text.isNullOrEmpty() || !parser.feed(text))) break
            if (line.terminated && !text.isNullOrEmpty()) parser.feed(text)
            consumed = line.end
        }
        return consumed
    }

    /**
     * The agent type and task of a subagent run from the `.meta.json` beside its transcript; sessions are `main`. Null for a new
     * subagent whose meta file is not there yet: it is looked at again next pass, as the role is read once.
     */
    private fun metaOf(f: FoundTranscript): Pair<String, String?>? {
        if (f.kind != "subagent") return "main" to null
        val file = f.path.resolveSibling("${f.path.nameWithoutExtension}.meta.json")
        if (!Files.exists(file) && now() - f.mtime < META_GRACE_MS) return null
        val meta = runCatching { Json.parseToJsonElement(Files.readString(file)) as JsonObject }.getOrNull()
        fun text(key: String) = (meta?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return (text("agentType") ?: "unknown") to text("description")?.let { TER.find(it)?.value }
    }

        private companion object {
        const val BASELINE = "baseline"
        const val CATEGORIES = "categories"
        const val META_GRACE_MS = 60_000L
        const val BATCH_FILES = 50
        val TER = Regex("TER-\\d+")
    }
}
