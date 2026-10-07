package codeloupe.events

import codeloupe.JsonFormat
import codeloupe.platform.Sqlite
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.sql.Connection

/** `<home>/events.db`: the event log, webhook subscriptions and deliveries. One connection, calls serialized. */
class EventStore(file: Path) : AutoCloseable {
    private val db: Connection = Sqlite.open(file, SCHEMA)
    private var writes = 0

    @Synchronized
    fun append(type: String, at: String, data: JsonObject): Event {
        val seq = db.prepareStatement("INSERT INTO events(at, type, data) VALUES (?, ?, ?)", arrayOf("seq")).use { s ->
            s.setString(1, at)
            s.setString(2, type)
            s.setString(3, data.toString())
            s.executeUpdate()
            s.generatedKeys.use { it.next(); it.getLong(1) }
        }
        if (++writes % PRUNE_EVERY == 0) prune()
        return Event(seq, at, type, data)
    }

    @Synchronized
    fun since(seq: Long, limit: Int): List<Event> =
        db.prepareStatement("SELECT seq, at, type, data FROM events WHERE seq > ? ORDER BY seq LIMIT ?").use { s ->
            s.setLong(1, seq)
            s.setInt(2, limit)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(Event(r.getLong(1), r.getString(2), r.getString(3), JsonFormat.json.parseToJsonElement(r.getString(4)) as JsonObject))
                }
            }
        }

    @Synchronized
    fun lastSeq(): Long = db.createStatement().use { s -> s.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM events").use { it.next(); it.getLong(1) } }

    @Synchronized
    fun putWebhook(webhook: Webhook) = upsert("webhooks", webhook.id, JsonFormat.json.encodeToString(Webhook.serializer(), webhook))

    @Synchronized
    fun removeWebhook(id: String): Boolean = db.prepareStatement("DELETE FROM webhooks WHERE id = ?").use { it.setString(1, id); it.executeUpdate() > 0 }

    @Synchronized
    fun webhooks(): List<Webhook> = db.createStatement().use { s ->
        s.executeQuery("SELECT record FROM webhooks ORDER BY rowid").use { r ->
            buildList { while (r.next()) add(JsonFormat.json.decodeFromString(Webhook.serializer(), r.getString(1))) }
        }
    }

    @Synchronized
    fun putDelivery(delivery: Delivery, body: String? = null) {
        db.prepareStatement(
            "INSERT INTO deliveries(id, state, record, body) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET state = excluded.state, record = excluded.record",
        ).use { s ->
            s.setString(1, delivery.id)
            s.setString(2, delivery.state)
            s.setString(3, JsonFormat.json.encodeToString(Delivery.serializer(), delivery))
            s.setString(4, body)
            s.executeUpdate()
        }
    }

    /** Deliveries still to be attempted, with their bodies: what a restarted daemon resumes. */
    @Synchronized
    fun pending(): List<Pair<Delivery, String>> = db.prepareStatement("SELECT record, body FROM deliveries WHERE state = ? ORDER BY rowid").use { s ->
        s.setString(1, Delivery.PENDING)
        s.executeQuery().use { r ->
            buildList { while (r.next()) add(JsonFormat.json.decodeFromString(Delivery.serializer(), r.getString(1)) to r.getString(2)) }
        }
    }

    @Synchronized
    fun deliveries(limit: Int): List<Delivery> = db.prepareStatement("SELECT record FROM deliveries ORDER BY rowid DESC LIMIT ?").use { s ->
        s.setInt(1, limit)
        s.executeQuery().use { r -> buildList { while (r.next()) add(JsonFormat.json.decodeFromString(Delivery.serializer(), r.getString(1))) } }
    }

    @Synchronized
    override fun close() = db.close()

    private fun upsert(table: String, id: String, record: String) {
        db.prepareStatement("INSERT INTO $table(id, record) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET record = excluded.record").use { s ->
            s.setString(1, id)
            s.setString(2, record)
            s.executeUpdate()
        }
    }

    private fun prune() {
        db.createStatement().use { s ->
            s.executeUpdate("DELETE FROM events WHERE seq <= (SELECT MAX(seq) FROM events) - $KEEP_EVENTS")
            s.executeUpdate("DELETE FROM deliveries WHERE state != '${Delivery.PENDING}' AND rowid <= (SELECT MAX(rowid) FROM deliveries) - $KEEP_DELIVERIES")
        }
    }

    private companion object {
        const val KEEP_EVENTS = 10_000
        const val KEEP_DELIVERIES = 2_000
        const val PRUNE_EVERY = 200

        // AUTOINCREMENT: a seq is never reused, even after the newest events are pruned.
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, type TEXT NOT NULL, data TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS webhooks (id TEXT PRIMARY KEY, record TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS deliveries (id TEXT PRIMARY KEY, state TEXT NOT NULL, record TEXT NOT NULL, body TEXT)",
        )
    }
}
