package codeloupe.secrets

import codeloupe.JsonFormat
import codeloupe.platform.IsoTime
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * What happened to the secrets, one JSON line each in `<home>/secrets/audit.log`: a read by a consumer (`env run: docker`, an
 * MCP server's name), a name created, rotated or removed. Lines are only ever appended; when the file passes [ROLL_BYTES] it
 * becomes `audit.log.1` (the one before is dropped), so about twice that much history stays. A line holds name, scope, consumer
 * and time, never a value. Writing is best effort: a full disk does not stop `env run`.
 */
class SecretAudit(private val file: Path, private val clock: () -> Instant = Instant::now, private val rollBytes: Long = ROLL_BYTES) {
    enum class Action { READ, CREATED, ROTATED, REMOVED }

    @Serializable
    data class Event(val at: String, val name: String, val scope: String, val action: Action, val consumer: String)

    /** Who read one secret, how often and when last. */
    @Serializable
    data class Consumer(val consumer: String, val reads: Int, val lastAt: String)

    private val older: Path = file.resolveSibling(file.fileName.toString() + ".1")
    private var cache: Pair<List<Long>, List<Event>>? = null

    fun record(action: Action, name: String, scope: String, consumer: String) {
        val line = JsonFormat.json.encodeToString(Event.serializer(), Event(IsoTime.of(clock()), name, scope, action, clean(consumer))) + "\n"
        runCatching {
            Files.createDirectories(file.parent)
            if (Files.exists(file) && Files.size(file) > rollBytes) Files.move(file, older, StandardCopyOption.REPLACE_EXISTING)
            Files.write(file, line.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /** Newest first. */
    @Synchronized
    fun recent(limit: Int, name: String? = null, scope: String? = null): List<Event> =
        events().asReversed().asSequence().filter { (name == null || it.name == name) && (scope == null || it.scope == scope) }.take(limit).toList()

    /** Per `name` and `scope` (as [key]), the readers, the most recent first. */
    @Synchronized
    fun consumers(): Map<String, List<Consumer>> =
        events().filter { it.action == Action.READ }.groupBy { key(it.name, it.scope) }.mapValues { (_, reads) ->
            reads.groupBy { it.consumer }.map { (who, own) -> Consumer(who, own.size, own.maxOf { it.at }) }.sortedByDescending { it.lastAt }
        }

    private fun events(): List<Event> {
        val stamp = listOf(older, file).flatMap { listOf(runCatching { Files.size(it) }.getOrDefault(-1), runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(-1)) }
        cache?.let { if (it.first == stamp) return it.second }
        val all = listOf(older, file).flatMap { path ->
            if (!Files.isRegularFile(path)) emptyList()
            else Files.readAllLines(path).mapNotNull { runCatching { JsonFormat.json.decodeFromString(Event.serializer(), it) }.getOrNull() }
        }
        cache = stamp to all
        return all
    }

    private fun clean(consumer: String) = consumer.filterNot { it.isISOControl() }.trim().take(MAX_CONSUMER).ifEmpty { "unknown" }

    companion object {
        const val ROLL_BYTES = 4L shl 20
        private const val MAX_CONSUMER = 80

        fun key(name: String, scope: String) = "$name|$scope"
    }
}
