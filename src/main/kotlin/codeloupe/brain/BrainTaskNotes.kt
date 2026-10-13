package codeloupe.brain

/** A task file read: its entries in file order and the state its `% key=value` lines add up to (the latest value of a key wins). */
class BrainTaskNotes(val id: String, val entries: List<BrainTaskEntry>, val state: Map<String, String>) {
    val lastAt: String? get() = entries.maxOfOrNull { it.at }

    fun summary(): BrainTaskSummary = BrainTaskSummary(id, lastAt, state, entries.size)
}
