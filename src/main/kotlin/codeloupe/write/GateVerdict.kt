package codeloupe.write

import codeloupe.config.WriteConfig
import kotlinx.serialization.Serializable

/**
 * What the transcripts of a period say about the gaps the `edit` tool closes: [wholeFileReads] reads of a whole code file that were
 * followed by an edit of it, [manualRenames] runs that renamed one identifier by hand in several files. [open] when either reaches its
 * threshold in [rule].
 */
@Serializable
data class GateVerdict(val at: String, val days: Int, val runs: Int, val wholeFileReads: Int, val manualRenames: Int, val open: Boolean) {
    fun render(rule: WriteConfig.GateRule): String =
        "write gate ${if (open) "open" else "closed"}: in $runs runs of the last $days days, $wholeFileReads whole-file reads followed by an edit " +
            "(opens at ${rule.wholeFileReads}), $manualRenames hand renames across files (opens at ${rule.manualRenames})"
}
