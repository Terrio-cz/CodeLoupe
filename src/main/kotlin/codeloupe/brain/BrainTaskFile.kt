package codeloupe.brain

import codeloupe.platform.IsoTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Reads the text of a task file as `brain.mjs note/state/reject` write it: `## <date> <time> <who>` (or `## REJECTED ...`) opens a section, every
 * non-blank line below it is one entry, and the engine stamps in UTC. Lines before the first section (the title) are not entries.
 */
object BrainTaskFile {
    private val header = Regex("""^## (REJECTED )?(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\s*(.*)$""")
    private val marker = Regex("""^(->|!P\d|[@%+!?>=~x-])(?=\s|$)""")
    private val anchors = Regex("""\s*<!--.*?-->\s*$""")

    /** The engine's order of state keys; others follow in first-seen order. */
    private val stateOrder = listOf("plan", "code", "rev", "test", "round", "cl", "land", "merge", "close", "hold")

    fun parse(id: String, text: String): BrainTaskNotes {
        val entries = ArrayList<BrainTaskEntry>()
        val state = LinkedHashMap<String, String>()
        var at: String? = null
        var who = ""
        var rejected = false
        text.lineSequence().forEachIndexed { index, raw ->
            val head = header.find(raw)
            if (head != null) {
                at = instant(head.groupValues[2], head.groupValues[3])
                who = head.groupValues[4].trim()
                rejected = head.groupValues[1].isNotEmpty()
                return@forEachIndexed
            }
            val stamp = at ?: return@forEachIndexed
            val line = anchors.replace(raw.trim(), "")
            if (line.isEmpty()) return@forEachIndexed
            val mark = marker.find(line)?.value.orEmpty()
            val body = line.removePrefix(mark).trim()
            if (mark == "%") body.split(Regex("\\s+")).forEach { pair -> pair.substringBefore('=', "").takeIf { it.isNotEmpty() }?.let { state[it] = pair.substringAfter('=') } }
            entries += BrainTaskEntry(index + 1, stamp, who, mark, body, rejected || mark == "x")
        }
        return BrainTaskNotes(id, entries, state.entries.sortedBy { stateOrder.indexOf(it.key).let { i -> if (i < 0) stateOrder.size else i } }.associate { it.key to it.value })
    }

    private fun instant(date: String, time: String): String? = try {
        IsoTime.of(LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.UTC))
    } catch (e: DateTimeParseException) {
        null
    }
}
