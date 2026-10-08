package codeloupe.write

import codeloupe.platform.IsoTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** One file of a write: its path, the SHA-1 of its bytes before (null = it did not exist) and after (null = it was removed). */
@Serializable
data class WrittenFile(val path: String, val before: String?, val after: String?)

@Serializable
data class WriteRecord(val t: String, val op: String, val root: String, val note: String, val files: List<WrittenFile>)

/** An append-only log of the writes the daemon made, `<home>/writes.jsonl`: what was changed, by which operation, from which bytes to which. */
class WriteJournal(private val file: Path) {
    @Synchronized
    fun append(op: String, root: String, note: String, files: List<WrittenFile>) {
        val line = JSON.encodeToString(WriteRecord(IsoTime.now(), op, root, note, files)) + "\n"
        Files.createDirectories(file.parent)
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** The records, oldest first. */
    fun read(): List<WriteRecord> =
        if (!Files.isRegularFile(file)) emptyList() else Files.readAllLines(file).filter { it.isNotBlank() }.mapNotNull { runCatching { JSON.decodeFromString<WriteRecord>(it) }.getOrNull() }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
