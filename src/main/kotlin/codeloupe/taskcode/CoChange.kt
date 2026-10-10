package codeloupe.taskcode

/**
 * The files that earlier tasks changed together with the touched ones and that this task is not yet known to change: the
 * model and documentation files a change of that code drags along (a field in a table mapping, a line in a setup guide), which
 * a short edit hides from every other section. A file counts once per earlier task; one that came with at least [MIN_TASKS]
 * of them and with a [MIN_SHARE] of them is listed, most frequent first.
 */
class CoChange(private val store: TaskCodeStore) {
    fun render(self: String, touched: List<String>, known: Collection<String>): String {
        if (touched.isEmpty()) return ""
        val overlap = LinkedHashMap<String, Int>()
        for (path in touched) {
            val tasks = store.commitsTouching(path).take(COMMITS_PER_FILE).flatMap { it.second }.filterNot { it.equals(self, ignoreCase = true) }.distinct()
            tasks.forEach { overlap.merge(it, 1, Int::plus) }
        }
        // Tasks that changed several of the touched files together say more than one that passed through a busy file.
        val close = overlap.filterValues { it >= 2 }.keys
        val considered = (if (close.size >= MIN_CLOSE) close else overlap.keys).take(MAX_TASKS)
        if (considered.size < MIN_TASKS) return ""
        val skip = (touched + known).toHashSet()
        val seen = LinkedHashMap<String, MutableList<String>>()
        for (task in considered) {
            val files = LandedTask.of(store, task).files(store).filter { (path, status) -> status != 'D' && path !in skip && !isTest(path) }
            files.keys.forEach { seen.getOrPut(it) { ArrayList() } += task }
        }
        val enough = maxOf(MIN_TASKS, Math.ceil(considered.size * MIN_SHARE).toInt())
        val lines = seen.entries.filter { it.value.size >= enough }.sortedByDescending { it.value.size }.take(MAX_LINES)
            .map { (path, tasks) -> "$path  ‹${tasks.size} of ${considered.size} earlier tasks: ${tasks.take(EXAMPLES).joinToString(", ")}›" }
        return lines.joinToString("\n")
    }

    private fun isTest(path: String) = "/src/test/" in "/$path" || "/test/" in "/$path"

    private companion object {
        const val COMMITS_PER_FILE = 40
        const val MAX_TASKS = 24
        const val MIN_TASKS = 2
        const val MIN_CLOSE = 3
        const val MIN_SHARE = 0.2
        const val MAX_LINES = 8
        const val EXAMPLES = 2
    }
}
