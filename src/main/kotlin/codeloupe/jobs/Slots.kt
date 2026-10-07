package codeloupe.jobs

import kotlinx.coroutines.CompletableDeferred

/**
 * Named resource slots (`gradle-test` ×2, `vps-test`, a docker stack per workspace). A job that needs a busy slot
 * suspends here, first come first served, until a holder ends — waiting costs neither a thread nor an agent turn.
 * A slot that is not configured exists only while someone holds or waits for it.
 */
class Slots(private val capacities: Map<String, Int>) {
    private class Waiter(val id: String) {
        val turn = CompletableDeferred<Unit>()
    }

    private class Slot(val capacity: Int) {
        val running = LinkedHashSet<String>()
        val waiting = ArrayDeque<Waiter>()
    }

    private val slots = HashMap<String, Slot>()

    fun capacity(name: String): Int = capacities[name] ?: 1

    /** Runs [block] holding [name]; [onQueued] runs once if the job has to wait. `waited` tells whether it did. */
    suspend fun <T> withSlot(name: String, id: String, onQueued: () -> Unit, block: suspend (waited: Boolean) -> T): T {
        val waiter = synchronized(this) {
            val slot = slots.getOrPut(name) { Slot(capacity(name)) }
            if (slot.running.size < slot.capacity) {
                slot.running += id
                null
            } else {
                Waiter(id).also { slot.waiting += it }
            }
        }
        if (waiter != null) {
            onQueued()
            try {
                waiter.turn.await()
            } catch (e: Throwable) {
                // Cancelled while queued; if the slot was handed over in the meantime, pass it on.
                val granted = synchronized(this) { slots[name]?.waiting?.remove(waiter) != true }
                if (granted) release(name, id)
                throw e
            }
        }
        try {
            return block(waiter != null)
        } finally {
            release(name, id)
        }
    }

    /** Jobs ahead of [id] in the queue of [name]. */
    fun ahead(name: String, id: String): Int = synchronized(this) {
        slots[name]?.waiting?.indexOfFirst { it.id == id }?.coerceAtLeast(0) ?: 0
    }

    fun snapshot(): List<SlotSnapshot> = synchronized(this) {
        (capacities.keys + slots.keys).sorted().map { name ->
            val slot = slots[name]
            SlotSnapshot(name, capacity(name), slot?.running?.toList().orEmpty(), slot?.waiting?.map { it.id }.orEmpty())
        }
    }

    private fun release(name: String, id: String) = synchronized(this) {
        val slot = slots[name] ?: return@synchronized
        slot.running -= id
        while (slot.running.size < slot.capacity) {
            val next = slot.waiting.removeFirstOrNull() ?: break
            slot.running += next.id
            next.turn.complete(Unit)
        }
        if (slot.running.isEmpty() && slot.waiting.isEmpty()) slots.remove(name)
    }
}
