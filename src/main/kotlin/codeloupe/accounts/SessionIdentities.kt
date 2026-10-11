package codeloupe.accounts

import codeloupe.JsonFormat
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * `<home>/session-identities.jsonl`: which login ran each Claude Code session, as the fingerprint of its account uuid, written by the
 * `SessionStart` hook and read by the transcript ingest (CL-226). Neither the uuid nor the e-mail of the account is ever stored here.
 * Append-only, the newest line of a session wins; a file past [COMPACT_BYTES] is rewritten without the sessions older than [KEEP_MS].
 * A line that cannot be read is skipped, and a failing write is dropped: a missing stamp only leaves a session undecided.
 */
class SessionIdentities(private val file: Path, private val now: () -> Long = System::currentTimeMillis) {
    @Serializable
    private class Entry(val s: String, val i: String, val t: Long)

    private val entries = HashMap<String, Entry>()
    private var seen: Pair<Long, Long>? = null
    private var view: Map<String, String> = emptyMap()

    /** Session id to fingerprint; the same instance until the file changes. */
    @Synchronized
    fun all(): Map<String, String> {
        reload()
        return view
    }

    @Synchronized
    fun record(session: String, identity: String) {
        reload()
        if (entries[session]?.i == identity) return
        runCatching {
            val entry = Entry(session, identity, now())
            if (size() > COMPACT_BYTES) compact()
            Files.createDirectories(file.parent)
            Files.writeString(file, JsonFormat.json.encodeToString(Entry.serializer(), entry) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            entries[session] = entry
            view = entries.mapValues { it.value.i }
            seen = signature()
        }
    }

    private fun reload() {
        val now = signature()
        if (now == seen) return
        entries.clear()
        runCatching {
            if (now != null) Files.newBufferedReader(file).useLines { lines ->
                for (line in lines) runCatching { JsonFormat.json.decodeFromString(Entry.serializer(), line) }.getOrNull()?.let { entries[it.s] = it }
            }
        }
        view = entries.mapValues { it.value.i }
        seen = now
    }

    private fun compact() {
        val keep = entries.values.filter { it.t >= now() - KEEP_MS }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, keep.joinToString("") { JsonFormat.json.encodeToString(Entry.serializer(), it) + "\n" })
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        entries.clear()
        keep.forEach { entries[it.s] = it }
    }

    private fun size(): Long = runCatching { Files.size(file) }.getOrDefault(0L)

    private fun signature(): Pair<Long, Long>? = runCatching { Files.getLastModifiedTime(file).toMillis() to Files.size(file) }.getOrNull()

    companion object {
        const val FILE = "session-identities.jsonl"
        private const val COMPACT_BYTES = 512L * 1024
        private const val KEEP_MS = 120L * 24 * 3_600_000
    }
}
