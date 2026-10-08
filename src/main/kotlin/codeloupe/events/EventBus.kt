package codeloupe.events

import codeloupe.platform.IsoTime
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

/**
 * Every event goes through here: scrubbed, numbered and stored, then pushed to live streams and matching webhooks.
 * Emitting is serialized, so streams see events in `seq` order.
 */
class EventBus(private val store: EventStore, private val webhooks: Webhooks) {
    private val flow = MutableSharedFlow<Event>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Live events; a reader that falls behind loses the oldest and catches up from [since] by `seq`. */
    val live: SharedFlow<Event> = flow

    @Synchronized
    fun emit(type: String, data: JsonObject): Event {
        val event = store.append(type, IsoTime.now(), Scrubber.json(data) as JsonObject)
        flow.tryEmit(event)
        webhooks.publish(event)
        return event
    }

    fun since(seq: Long, limit: Int): List<Event> = store.since(seq, limit)

    fun lastSeq(): Long = store.lastSeq()

    fun epoch(): String = store.epoch()
}
