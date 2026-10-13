package codeloupe.jobs

/** The slots as `job slots` and `job status` print them: capacity in force and where it comes from, holders and the queue with their times. */
object SlotReport {
    fun text(slots: List<SlotSnapshot>): String =
        if (slots.isEmpty()) "no slots (none configured, none in use)" else slots.joinToString("\n", transform = ::line)

    fun line(slot: SlotSnapshot): String {
        val source = when {
            slot.invalid != null -> "config.json has ${slot.invalid}, not a positive whole number: 1"
            slot.configured == null -> "not in config.json: 1"
            else -> "config.json"
        }
        val holders = slot.holders.joinToString(", ") { "${it.id} ${since(it)}" }.ifEmpty { "none" }
        val queue = slot.queue.joinToString(", ") { "${it.id} ${since(it)}" }
        return "${slot.name}: ${slot.running.size} of ${slot.capacity} ($source) · holding $holders" +
            if (queue.isEmpty()) "" else " · waiting ${slot.queue.size}: $queue"
    }

    private fun since(entry: SlotEntry) = JobReport.duration(entry.seconds * 1000)
}
