package codeloupe.reconcile

import codeloupe.JsonFormat
import codeloupe.config.ReconcileConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

/**
 * How far the cleanup of each target has got: failed or blocked attempts, when the next one is due, and why the last
 * one did not work. Kept in a file, so the backoff survives a restart of the daemon or the PC. A target that is no
 * longer planned is forgotten.
 */
class ReconcileState(private val file: Path, private val config: ReconcileConfig, private val now: () -> Instant = Instant::now) {
    @Serializable
    data class Item(
        val attempts: Int,
        val nextAttempt: String,
        val lastError: String,
        /** Somebody confirmed this removal: its retries need no second confirmation. */
        val confirmed: Boolean = false,
    )

    private val items = HashMap<String, Item>()

    init {
        runCatching { items.putAll(JsonFormat.json.decodeFromString(SERIALIZER, Files.readString(file))) }
    }

    @Synchronized
    fun get(key: String): Item? = items[key]

    /** Whether an attempt at [key] may be made now. */
    @Synchronized
    fun due(key: String): Boolean = items[key]?.let { dueNow(it) } ?: true

    /** Whether any target is waiting for a retry that is due. */
    @Synchronized
    fun anyDue(): Boolean = items.values.any(::dueNow)

    /** Records a failed or blocked attempt; the wait doubles from `retryBaseMinutes` up to `retryMaxMinutes`. */
    @Synchronized
    fun failed(key: String, error: String, confirmed: Boolean = false) {
        val attempts = (items[key]?.attempts ?: 0) + 1
        val minutes = minOf(config.retryMaxMinutes.toLong(), config.retryBaseMinutes.toLong() shl minOf(attempts - 1, 20))
        items[key] = Item(attempts, now().plus(Duration.ofMinutes(minutes)).toString(), error.take(300), confirmed || items[key]?.confirmed == true)
        save()
    }

    @Synchronized
    fun succeeded(key: String) {
        if (items.remove(key) != null) save()
    }

    /** Drops everything but [keys]: what is no longer planned is done or no longer wanted. */
    @Synchronized
    fun retain(keys: Set<String>) {
        if (items.keys.retainAll(keys)) save()
    }

    // A wait that cannot be read is over: the attempt is made, and the entry gets a fresh one.
    private fun dueNow(item: Item): Boolean = runCatching { Instant.parse(item.nextAttempt) <= now() }.getOrDefault(true)

    private fun save() {
        runCatching {
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, JsonFormat.json.encodeToString(SERIALIZER, items))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), Item.serializer())
    }
}
