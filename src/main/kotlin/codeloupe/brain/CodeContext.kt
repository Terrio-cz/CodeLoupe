package codeloupe.brain

import kotlinx.serialization.Serializable

/**
 * The code a question is about, as the index and the task history see it: the files, the names their declarations answer to (every
 * dotted suffix of a fully qualified name, and the type names), the modules, the files that call them and the tasks that changed them.
 * Empty when the files are unknown or the index could not be read; the ranking then goes by text alone.
 */
class CodeContext(
    val files: List<CodeFileFacts> = emptyList(),
    val symbolKeys: Set<String> = emptySet(),
    val callerPaths: Set<String> = emptySet(),
    val taskIds: Set<String> = emptySet(),
    val notes: List<String> = emptyList(),
    val root: String? = null,
) {
    val paths: List<String> = files.map { it.path }
    val modules: Set<String> = files.map { it.module.lowercase() }.filter { it.isNotEmpty() }.toSet()
    val isEmpty: Boolean get() = files.isEmpty()

    fun summary(): CodeSummary = CodeSummary(
        root, files, notes,
        if (files.isEmpty()) "no file of the question is in the code index" else "${files.size} file(s), ${symbolKeys.size} names, ${callerPaths.size} calling file(s), ${taskIds.size} task(s) that changed them",
    )

    @Serializable
    data class CodeSummary(val root: String?, val files: List<CodeFileFacts>, val notes: List<String>, val summary: String)

    companion object {
        val NONE = CodeContext()
        fun unavailable(reason: String) = CodeContext(notes = listOf(reason))
    }
}
