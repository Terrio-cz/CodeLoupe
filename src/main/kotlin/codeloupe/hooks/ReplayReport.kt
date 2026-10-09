package codeloupe.hooks

import kotlinx.serialization.Serializable

/** What the steering hook would have said to the calls of old transcripts. */
@Serializable
data class ReplayReport(
    val transcripts: Int,
    val calls: Map<String, Int>,
    val advised: Int,
    val denied: Int,
    /** Advice by kind and suggested tool: `search:usages -> usages` and so on. */
    val byKind: Map<String, Int>,
    val passed: Map<String, Int>,
    /** Per agent role: calls examined and calls advised. */
    val byRole: Map<String, RoleCounts>,
    /** Per search or read program, how its calls were judged: `advised` or the reason they were left alone. */
    val programs: Map<String, Map<String, Int>> = emptyMap(),
    val mode: String,
    val minLines: Int,
) {
    @Serializable
    data class RoleCounts(val calls: Int = 0, val advised: Int = 0)

    fun render(): String {
        val total = calls.values.sum()
        val spoken = advised + denied
        fun share(n: Int) = if (total == 0) "0" else "%.1f".format(java.util.Locale.ROOT, n * 100.0 / total)
        return buildString {
            appendLine("replay of $transcripts transcripts through the steering hook (mode $mode, large from $minLines lines; files as they are on disk today)")
            appendLine("$total shell and read calls: " + calls.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" })
            appendLine("would be advised: $spoken (${share(spoken)} %)" + if (denied > 0) ", $denied of them refused first" else "")
            byKind.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.value}  ${it.key}") }
            appendLine("left alone: " + passed.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" })
            appendLine("by program (how its calls were judged):")
            programs.entries.sortedByDescending { it.value.values.sum() }.forEach { (program, verdicts) ->
                appendLine("  $program  " + verdicts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" })
            }
            appendLine("by agent role (advised/calls):")
            byRole.entries.sortedByDescending { it.value.calls }.take(ROLES).forEach { appendLine("  ${it.key}  ${it.value.advised}/${it.value.calls}") }
        }.trimEnd()
    }

    private companion object {
        const val ROLES = 10
    }
}
