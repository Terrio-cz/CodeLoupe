package codeloupe.brain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The index of each workspace brain, rebuilt from `brain.mjs export` whenever the files it was built from changed. Every query asks for the
 * index, and asking compares [BrainSignature] with the one it was built at, so an entry appended a moment ago is in the next answer. The brain files
 * stay the only truth: this class holds a copy it can throw away.
 */
class BrainIndexes(private val engine: BrainEngine, private val onBuilt: (BrainRoot, BrainIndex) -> Unit = { _, _ -> }) {
    private class Built(val index: BrainIndex, val signature: String)

    private val built = ConcurrentHashMap<String, Built>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(root: BrainRoot): BrainIndex {
        val lock = locks.computeIfAbsent(root.id) { Mutex() }
        return lock.withLock {
            val before = built[root.id]
            val signature = before?.let { BrainSignature.of(it.index.snapshot.paths) }
            if (before != null && signature == before.signature) return@withLock before.index
            val snapshot = try {
                BrainSnapshot.parse(engine.run(root.path, listOf("export")))
            } catch (e: BrainException) {
                // A stale index beats none: the brain is still readable by the engine itself.
                if (before != null) return@withLock before.index
                throw e
            }
            val index = BrainIndex(snapshot)
            built[root.id] = Built(index, signature ?: BrainSignature.of(snapshot.paths))
            onBuilt(root, index)
            index
        }
    }

    /** The index as last built, without looking at the files: for answers that may be a little old (a note on a symbol). */
    fun cached(root: BrainRoot): BrainIndex? = built[root.id]?.index
}
