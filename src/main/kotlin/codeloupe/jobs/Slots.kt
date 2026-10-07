package codeloupe.jobs

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * Named resource slots (`gradle-test` ×2, `vps-test`, a docker stack per workspace). A job that needs a busy slot
 * suspends here, first come first served, until a holder ends — waiting costs neither a thread nor an agent turn.
 */
class Slots(private val capacities: Map<String, Int>) {
    private class Slot(val capacity: Int) {
        val permits = Semaphore(capacity)
        val running = LinkedHashSet<String>()
        val waiting = LinkedHashSet<String>()
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    fun capacity(name: String): Int = capacities[name] ?: 1

    /** Runs [block] holding [name]; [onQueued] runs once if the job has to wait. `waited` tells whether it did. */
    suspend fun <T> withSlot(name: String, id: String, onQueued: () -> Unit, block: suspend (waited: Boolean) -> T): T {
        val slot = slots.computeIfAbsent(name) { Slot(capacity(it)) }
        var waited = false
        if (!slot.permits.tryAcquire()) {
            synchronized(slot) { slot.waiting += id }
            onQueued()
            try {
                slot.permits.acquire()
            } finally {
                synchronized(slot) { slot.waiting -= id }
            }
            waited = true
        }
        synchronized(slot) { slot.running += id }
        try {
            return block(waited)
        } finally {
            synchronized(slot) { slot.running -= id }
            slot.permits.release()
        }
    }

    /** Jobs ahead of [id] in the queue of [name]. */
    fun ahead(name: String, id: String): Int = slots[name]?.let { s -> synchronized(s) { s.waiting.indexOf(id).coerceAtLeast(0) } } ?: 0

    fun snapshot(): List<SlotSnapshot> {
        val names = (capacities.keys + slots.keys).sorted()
        return names.map { name ->
            val slot = slots[name]
            if (slot == null) {
                SlotSnapshot(name, capacity(name), emptyList(), emptyList())
            } else {
                synchronized(slot) { SlotSnapshot(name, slot.capacity, slot.running.toList(), slot.waiting.toList()) }
            }
        }
    }
}
