package codeloupe.taskcode

import codeloupe.tracker.IssueComment
import codeloupe.tracker.Times

/**
 * The comment thread of an issue within a size budget: every comment, oldest first, whitespace folded to one line. Short
 * comments stay whole and the long ones (evidence, pasted output) share what is left, each cut in the middle, so the decisions
 * in a long thread are all there and none of them is cut away by a neighbour.
 */
object CommentDigest {
    fun render(comments: List<IssueComment>, total: Int = TOTAL): String {
        if (comments.isEmpty()) return ""
        val rows = comments.map { it to FOLD.replace(it.text.trim(), " ") }
        val shares = shares(rows.map { it.second.length }, total)
        val lines = rows.mapIndexed { i, (comment, text) ->
            val body = if (text.length > shares[i]) ends(text, shares[i]) else text
            "${comment.author ?: "?"} ${Times.short(comment.created)}: $body"
        }
        val cut = rows.indices.count { rows[it].second.length > shares[it] }
        val header = "${comments.size} comments" + if (cut > 0) ", $cut cut (issue sections=[\"comments\"] for the full text)" else ""
        return header + "\n" + lines.joinToString("\n")
    }

    /** A long comment opens with what it is and closes with what it concludes: keep both ends. */
    private fun ends(text: String, share: Int): String {
        val head = share * HEAD / 100
        return text.take(head).trimEnd() + " … +${text.length - share} chars … " + text.takeLast(share - head).trimStart()
    }

    /** Each length's share of [total]: the shortest first take what they need, the rest split evenly, never below [FLOOR]. */
    private fun shares(lengths: List<Int>, total: Int): List<Int> {
        val shares = IntArray(lengths.size)
        var left = total
        var remaining = lengths.size
        for (i in lengths.indices.sortedBy { lengths[it] }) {
            val fair = maxOf(left / remaining, FLOOR)
            shares[i] = minOf(lengths[i], fair, CEILING)
            left -= shares[i]
            remaining--
        }
        return shares.toList()
    }

    private val FOLD = Regex("\\s+")
    private const val TOTAL = 5000
    private const val HEAD = 60
    private const val FLOOR = 160
    private const val CEILING = 1500
}
