package codeloupe.know

/** The compact line of a fact: `k17 pattern area:docker  text (TER-12)`, with `STALE: reason` between when an anchor says it may be out of date. */
object FactLine {
    private const val MAX_TEXT = 240

    fun of(fact: Fact): String = buildString {
        append(fact.id).append(' ').append(fact.kind.word).append(' ').append(fact.scopeRef)
        when (fact.status) {
            FactStatus.STALE -> append(" STALE: ").append(fact.staleReason ?: "an anchor changed")
            FactStatus.SUPERSEDED -> append(" SUPERSEDED")
            FactStatus.ACTIVE -> Unit
        }
        append("  ").append(clip(fact.text))
        fact.source.task?.let { append(" (").append(it).append(')') }
    }

    private fun clip(text: String) = if (text.length <= MAX_TEXT) text else text.take(MAX_TEXT - 1).trimEnd() + "…"
}
