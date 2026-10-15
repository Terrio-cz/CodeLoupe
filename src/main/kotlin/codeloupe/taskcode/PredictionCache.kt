package codeloupe.taskcode

import java.util.concurrent.ConcurrentHashMap

/**
 * Touch predictions of open tasks, shared by `task_context`, `task_code` and `dispatch_plan` of one daemon. A prediction holds
 * while the issue mentions the same things and no landing since changed a file those mentions name or could resolve to: a state
 * change or a comment does not predict a task again, and neither does a landing elsewhere in the repository. Before, every landing
 * made the whole dispatch pool predicted again, 51-131 s for the first plan after it (CL-256).
 */
class PredictionCache {
    /** The base commit a prediction was made on (or last found to hold on), and the mentions it was made from. */
    private class Kept(val base: String?, val mentions: List<Mention>, val result: TouchPrediction.Result)

    private val kept = ConcurrentHashMap<String, Kept>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val landings = ConcurrentHashMap<String, LandingDiff>()

    /** The prediction of task [id] in the repository at [commonDir] on [base], made by [predict] only when no kept one holds. */
    fun get(commonDir: String, id: String, base: String?, mentions: List<Mention>, predict: () -> TouchPrediction.Result): TouchPrediction.Result {
        val key = "$commonDir|$id"
        // One computation per task at a time: dispatch plans that overlap after a landing ask for the same pool.
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            kept[key]?.let { old -> if (old.mentions == mentions && holds(key, old, base, commonDir)) return old.result }
            return predict().also { kept[key] = Kept(base, mentions, it) }
        }
    }

    private fun holds(key: String, old: Kept, base: String?, commonDir: String): Boolean {
        if (old.base == base) return true
        if (old.base == null || base == null) return false
        if (landings.size > MAX_LANDINGS) landings.clear()
        // One git diff per pair of bases, whichever task asks first.
        val diff = landings.computeIfAbsent("$commonDir|${old.base}..$base") { runCatching { LandingDiff.of(commonDir, old.base, base) }.getOrElse { LandingDiff(null) } }
        val files = diff.files ?: return false
        if (TouchPrediction.touchedBy(old.mentions, old.result, files)) return false
        kept[key] = Kept(base, old.mentions, old.result)
        return true
    }

    private companion object {
        const val MAX_LANDINGS = 32
    }
}
