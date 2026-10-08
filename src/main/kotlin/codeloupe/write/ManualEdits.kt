package codeloupe.write

import codeloupe.metrics.Run
import codeloupe.metrics.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Finds, in the tool calls of a run, the two things a coder does by hand that the `edit` tool does in one call. */
internal object ManualEdits {
    private const val WINDOW_TURNS = 3
    private const val RENAME_FILES = 3
    private val TOKEN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val EDITS = setOf("Edit", "MultiEdit")

    /** Reads of a whole code file followed within a few turns by an edit of the same file. */
    fun wholeFileReads(run: Run): Int {
        val edits = run.tools.filter { it.name in EDITS && it.category == "code_write" }
        return run.tools.count { read ->
            read.category == "code_read" && !read.partial && read.file != null &&
                edits.any { it.file == read.file && it.seq > read.seq && it.turn - read.turn <= WINDOW_TURNS }
        }
    }

    /** True when one identifier was replaced by the same other one in at least three files of the run. */
    fun renamedByHand(run: Run): Boolean {
        val files = HashMap<Pair<String, String>, MutableSet<String>>()
        for (call in run.tools.filter { it.name in EDITS && it.file != null }) {
            for (pair in pairs(call)) files.getOrPut(pair) { HashSet() } += call.file!!
        }
        return files.values.any { it.size >= RENAME_FILES }
    }

    private fun pairs(call: ToolCall): List<Pair<String, String>> {
        val edits = (call.input["edits"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(call.input)
        return edits.mapNotNull { edit -> identifierSwap(text(edit, "old_string"), text(edit, "new_string")) }
    }

    private fun text(edit: JsonObject, key: String) = (edit[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    // The same text but for one identifier, which is another one everywhere it differs.
    private fun identifierSwap(old: String, new: String): Pair<String, String>? {
        if (old.isBlank() || old == new) return null
        val before = TOKEN.findAll(old).map { it.value }.toList()
        val after = TOKEN.findAll(new).map { it.value }.toList()
        if (before.size != after.size || old.replace(TOKEN, "_") != new.replace(TOKEN, "_")) return null
        val swaps = before.indices.filter { before[it] != after[it] }.map { before[it] to after[it] }.distinct()
        return swaps.singleOrNull()
    }
}
