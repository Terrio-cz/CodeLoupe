package codeloupe.know

/** What a repeated write does to the fact already stored: the newer text and facts win, links and anchors add up, the first id and date stay. */
object FactMerge {
    fun of(old: Fact, incoming: Fact): Fact = old.copy(
        kind = if (incoming.kind != FactKind.OTHER) incoming.kind else old.kind,
        text = incoming.text,
        source = FactSource(incoming.source.task ?: old.source.task, incoming.source.run ?: old.source.run),
        verifiedAtCommit = incoming.verifiedAtCommit ?: old.verifiedAtCommit,
        supersedes = (old.supersedes + incoming.supersedes).distinct(),
        related = (old.related + incoming.related).distinct(),
        anchors = anchors(old.anchors, incoming.anchors),
        updatedAt = incoming.updatedAt,
        status = FactStatus.ACTIVE,
        staleReason = null,
    )

    // An anchor of the same place replaces the older one when it carries a fingerprint; one without keeps the fingerprint there is.
    private fun anchors(old: List<Anchor>, incoming: List<Anchor>): List<Anchor> {
        fun key(a: Anchor) = Triple(a.root, a.path, a.symbol)
        val before = old.associateBy(::key)
        val kept = incoming.map { new -> if (new.fingerprinted) new else before[key(new)] ?: new }
        val replaced = kept.map(::key).toSet()
        return old.filter { key(it) !in replaced } + kept
    }
}
