package codeloupe.jobs

import kotlinx.coroutines.CompletableDeferred
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Named resource slots (`gradle-test` ×2, `vps-test`, a docker stack per workspace). A job that needs a busy slot
 * suspends here, in queue order, until a holder ends — waiting costs neither a thread nor an agent turn.
 * A slot that is not configured exists only while someone holds or waits for it.
 *
 * Capacities change at runtime ([configure]): a raised one hands the freed places to the first waiters at once, a lowered
 * one only stops handing out places until enough holders have ended — nobody is ever taken out of a slot.
 */
class Slots(capacities: Map<String, Int> = emptyMap(), private val clock: Clock = Clock.systemUTC()) {
    /** A place in the queue of [name], taken synchronously so that the order of [enter] calls is the order of the queue. */
    class Ticket internal constructor(val name: String, val id: String, val since: Instant) {
        internal val turn = CompletableDeferred<Unit>()

        /** Granted when it entered: it never waited. */
        internal var atOnce = false
    }

    private class Slot {
        /** Holders and since when they hold the slot. */
        val running = LinkedHashMap<String, Instant>()
        val waiting = ArrayList<Ticket>()
    }

    private val slots = HashMap<String, Slot>()
    private var capacities: Map<String, Int> = capacities
    private var invalid: Map<String, String> = emptyMap()

    @Synchronized
    fun capacity(name: String): Int = capacities[name] ?: 1

    /**
     * The capacities from `config.json` ([invalid]: entries there that are not a positive whole number, shown in [snapshot] so a
     * misconfigured slot is visible). Waiters get the places a raise freed at once.
     */
    @Synchronized
    fun configure(capacities: Map<String, Int>, invalid: Map<String, String> = emptyMap()) {
        this.capacities = capacities
        this.invalid = invalid
        for (name in slots.keys.toList()) fill(name)
    }

    @Synchronized
    fun configured(): Map<String, Int> = capacities

    /** Queues [id] on [name] (or grants it a place right away); [since] is when the job was submitted. */
    @Synchronized
    fun enter(name: String, id: String, since: Instant = clock.instant()): Ticket {
        val slot = slots.getOrPut(name) { Slot() }
        val ticket = Ticket(name, id, since)
        if (slot.waiting.isEmpty() && slot.running.size < capacity(name)) {
            slot.running[id] = clock.instant()
            ticket.atOnce = true
            ticket.turn.complete(Unit)
        } else {
            slot.waiting += ticket
        }
        return ticket
    }

    /** Takes [id] in as a holder whatever the capacity: a job a restarted daemon found still running. */
    @Synchronized
    fun adopt(name: String, id: String, since: Instant) {
        slots.getOrPut(name) { Slot() }.running[id] = since
    }

    /** Runs [block] holding the slot of [ticket]; [onQueued] runs once if the job has to wait. `waited` tells whether it did. */
    suspend fun <T> hold(ticket: Ticket, onQueued: () -> Unit, block: suspend (waited: Boolean) -> T): T {
        if (!ticket.atOnce) {
            onQueued()
            try {
                ticket.turn.await()
            } catch (e: Throwable) {
                leave(ticket)
                throw e
            }
        }
        try {
            return block(!ticket.atOnce)
        } finally {
            release(ticket.name, ticket.id)
        }
    }

    /** Gives up [ticket] without having run: out of the queue, or, if its place was granted in the meantime, the place passed on. */
    @Synchronized
    fun leave(ticket: Ticket) {
        val slot = slots[ticket.name] ?: return
        if (slot.waiting.remove(ticket)) {
            if (slot.running.isEmpty() && slot.waiting.isEmpty()) slots.remove(ticket.name)
        } else {
            release(ticket.name, ticket.id)
        }
    }

    /** Ends the hold of [id] on [name] and hands the place on. */
    @Synchronized
    fun release(name: String, id: String) {
        val slot = slots[name] ?: return
        slot.running.remove(id)
        fill(name)
    }

    /** Jobs ahead of [id] in the queue of [name]. */
    @Synchronized
    fun ahead(name: String, id: String): Int = slots[name]?.waiting?.indexOfFirst { it.id == id }?.coerceAtLeast(0) ?: 0

    /** The slot [id] waits in, or null when it waits in none. */
    @Synchronized
    fun waitingIn(id: String): String? = slots.entries.firstOrNull { (_, slot) -> slot.waiting.any { it.id == id } }?.key

    /** Whether [id] holds a place in some slot. */
    @Synchronized
    fun holds(id: String): Boolean = slots.values.any { id in it.running }

    /**
     * Moves the waiting job [id] within its slot's queue; the new order of that queue. Holders never move.
     * Throws [IllegalArgumentException] when [id] does not wait, or the job [position] names does not wait in the same slot.
     */
    @Synchronized
    fun move(id: String, position: QueuePosition): Pair<String, List<String>> {
        val name = waitingIn(id) ?: throw IllegalArgumentException(if (holds(id)) "$id is running: only waiting jobs move" else "$id does not wait for a slot")
        val waiting = slots.getValue(name).waiting
        val ticket = waiting.first { it.id == id }
        val anchor = when (position) {
            QueuePosition.First -> null
            is QueuePosition.Before -> position.id
            is QueuePosition.After -> position.id
        }
        require(anchor != id) { "a job cannot move relative to itself" }
        if (anchor != null && waiting.none { it.id == anchor }) {
            throw IllegalArgumentException("$anchor does not wait in slot $name" + if (slots[name]?.running?.containsKey(anchor) == true) " (it runs)" else "")
        }
        waiting.remove(ticket)
        val at = when (position) {
            QueuePosition.First -> 0
            is QueuePosition.Before -> waiting.indexOfFirst { it.id == anchor }
            is QueuePosition.After -> waiting.indexOfFirst { it.id == anchor } + 1
        }
        waiting.add(at, ticket)
        return name to waiting.map { it.id }
    }

    /** Every waiting job's place, per slot, in queue order. */
    @Synchronized
    fun queues(): Map<String, List<String>> = slots.mapValues { (_, slot) -> slot.waiting.map { it.id } }.filterValues { it.isNotEmpty() }

    @Synchronized
    fun snapshot(): List<SlotSnapshot> {
        val now = clock.instant()
        fun entry(id: String, since: Instant) = SlotEntry(id, since.toString(), Duration.between(since, now).seconds.coerceAtLeast(0))
        return (capacities.keys + invalid.keys + slots.keys).sorted().map { name ->
            val slot = slots[name]
            SlotSnapshot(
                name = name, capacity = capacity(name), running = slot?.running?.keys?.toList().orEmpty(), waiting = slot?.waiting?.map { it.id }.orEmpty(),
                configured = capacities[name], invalid = invalid[name],
                holders = slot?.running?.map { (id, since) -> entry(id, since) }.orEmpty(),
                queue = slot?.waiting?.map { entry(it.id, it.since) }.orEmpty(),
            )
        }
    }

    private fun fill(name: String) {
        val slot = slots[name] ?: return
        while (slot.running.size < capacity(name)) {
            val next = slot.waiting.removeFirstOrNull() ?: break
            slot.running[next.id] = clock.instant()
            next.turn.complete(Unit)
        }
        if (slot.running.isEmpty() && slot.waiting.isEmpty()) slots.remove(name)
    }
}
