package codeloupe.know

import codeloupe.git.Git
import codeloupe.platform.IsoTime
import java.nio.file.Path

/**
 * The knowledge store: facts with scope, kind, source and anchors to code, searched by relevance. Writing a near-identical fact
 * of the same scope updates the older one. Anchors are read through the index whenever a fact is shown, so a fact whose declaration
 * changed since it was verified comes back marked stale. The database `know.db` is opened on first use.
 */
class Know(
    file: Path,
    private val checker: AnchorChecker,
    /** The checkout anchors are relative to when neither the fact nor the anchor names one. */
    private val defaultRoot: String? = null,
    private val head: (String) -> String? = { root -> Git.run(root, "rev-parse", "HEAD", allowFail = true)?.trim()?.ifEmpty { null } },
    private val now: () -> String = IsoTime::now,
) : AutoCloseable {
    private val opened = lazy { KnowStore(file) }
    private val store by opened

    suspend fun put(draft: FactDraft): PutResult {
        val scope = ScopeRef.parse(draft.scope)
        val text = FactText.clean(draft.text)
        val root = draft.root ?: defaultRoot
        var anchors = resolve(draft, scope, root)
        var verifiedAt = draft.verifiedAtCommit
        if (draft.verify && anchors.isNotEmpty()) {
            val readings = checker.readings(anchors)
            val fingerprinted = anchors.map { checker.fingerprint(it, readings.getValue(it)) }
            if (fingerprinted.any { it.fingerprinted }) {
                verifiedAt = verifiedAt ?: fingerprinted.first { it.fingerprinted }.let { head(it.root) }
                anchors = fingerprinted
            }
        }
        // A file or class the text names without a directory is anchored where the index finds it, for existence only: a mention is not a claim about its code.
        if (root != null) anchors = anchors + checker.named(root, NamedCode.of(text)).filter { named -> anchors.none { it.place == named.place } }
        val stamp = now()
        val fact = Fact(
            id = "", scope = scope.kind, target = scope.target, kind = FactKind.parse(draft.kind), text = text, source = FactSource(draft.task, draft.run),
            verifiedAtCommit = verifiedAt, supersedes = draft.supersedes, related = draft.related, anchors = anchors,
            createdAt = draft.createdAt ?: stamp, updatedAt = stamp, status = if (draft.superseded) FactStatus.SUPERSEDED else FactStatus.ACTIVE,
        )
        val stored = store.upsert(fact, FactText.words(text))
        val replaced = store.supersede(draft.supersedes, stored.fact.id, stamp)
        return PutResult(if (stored.added) "added" else "updated", check(listOf(stored.fact)).first.single(), replaced)
    }

    fun get(id: String): Fact? = store.get(id)

    /**
     * The best [limit] facts for [question], each as a compact line; anchored ones are read first, so a stale fact says so. With an [anchor]
     * (`Foo.kt`, `src/Foo.kt`, `Foo`, `Foo.parse`, `src/Foo.kt#Foo.parse`) only the facts anchored to that code; the question may then be left out,
     * and the most recently updated facts come first.
     */
    suspend fun search(
        question: String = "",
        scope: String? = null,
        kind: String? = null,
        limit: Int = DEFAULT_LIMIT,
        includeSuperseded: Boolean = false,
        anchor: String? = null,
    ): List<KnowHit> {
        val query = anchor?.takeIf { it.isNotBlank() }?.let { AnchorRef.parse(it) }
        val match = FtsMatch.of(question)
        if (match == null && query == null) return emptyList()
        val filter = KnowFilter(ScopeRef.parseAll(scope), kind.orEmpty().split(',').filter { it.isNotBlank() }.map(FactKind::parse), includeSuperseded, query)
        val cap = limit.coerceIn(1, MAX_LIMIT)
        val found = store.search(match, filter, if (query == null) cap else ANCHOR_SCAN)
        val picked = if (query == null) found else found.filter { fact -> fact.anchors.any { AnchorMatch.matches(it, query) } }.take(cap)
        return check(picked).first.map { KnowHit(it, FactLine.of(it)) }
    }

    /** Reads the anchors of every fact that has one, stores what changed, and answers the stale ones. */
    suspend fun stale(): StaleReport {
        val anchored = store.anchored()
        val (facts, unreachable) = check(anchored)
        return StaleReport(anchored.size, unreachable, facts.filter { it.status == FactStatus.STALE }.map { KnowHit(it, FactLine.of(it)) })
    }

    /** Records that the fact holds now: its anchors are fingerprinted and the commit of their checkout stored. Null for an unknown id. */
    suspend fun verify(id: String): VerifyResult? {
        val fact = store.get(id) ?: return null
        val readings = checker.readings(fact.anchors)
        val gone = fact.anchors.filter { a -> readings.getValue(a).let { !it.reachable || !it.fileFound || !it.symbolFound } }
        if (gone.isNotEmpty()) {
            val why = gone.joinToString("; ") { "${it.label}: ${readings.getValue(it).error ?: "not found"}" }
            return VerifyResult(false, check(listOf(fact)).first.single(), "cannot verify, anchor not found: $why")
        }
        val anchors = fact.anchors.map { checker.fingerprint(it, readings.getValue(it)) }
        val commit = anchors.firstOrNull { it.fingerprinted }?.let { head(it.root) } ?: fact.verifiedAtCommit
        val verified = fact.copy(anchors = anchors, verifiedAtCommit = commit, status = FactStatus.ACTIVE, staleReason = null, updatedAt = now())
        store.update(verified)
        return VerifyResult(true, verified)
    }

    override fun close() {
        if (opened.isInitialized()) store.close()
    }

    // Anchors named by the writer, plus the one a file or symbol scope implies; each needs a checkout to be read in.
    private fun resolve(draft: FactDraft, scope: ScopeRef, root: String?): List<Anchor> {
        val implied = when {
            root == null -> emptyList()
            scope.kind == FactScope.FILE -> listOf(AnchorRef(path = CitedPaths.clean(scope.target)))
            scope.kind == FactScope.SYMBOL -> listOf(AnchorRef.parse(scope.target.takeIf { '#' in it } ?: "#${scope.target}"))
            else -> emptyList()
        }
        return (draft.anchors + implied).map { ref ->
            val at = ref.root ?: root ?: throw IllegalArgumentException("an anchor needs a checkout: send root, or start the daemon with a default root")
            Anchor(at, ref.path?.let(::relative), ref.symbol)
        }.distinct()
    }

    // An anchor names something inside its checkout: a path that leaves it would probe the rest of the disk.
    private fun relative(path: String): String {
        val clean = CitedPaths.clean(path)
        require(clean.isNotEmpty() && !clean.startsWith("/") && ':' !in clean && clean.split('/').none { it == ".." }) { "an anchor path is relative to its checkout: $path" }
        return clean
    }

    // Reads the anchors of [facts] and stores a changed status; unreachable checkouts leave the status as it is.
    private suspend fun check(facts: List<Fact>): Pair<List<Fact>, Int> {
        val anchored = facts.filter { it.anchors.isNotEmpty() && it.status != FactStatus.SUPERSEDED }
        if (anchored.isEmpty()) return facts to 0
        val readings = checker.readings(anchored.flatMap { it.anchors })
        var unreachable = 0
        val checked = facts.map { fact ->
            if (fact !in anchored) return@map fact
            when (val verdict = checker.verdict(fact, readings)) {
                is AnchorChecker.Verdict.Stale ->
                    if (fact.status == FactStatus.STALE && fact.staleReason == verdict.reason) fact else fact.copy(status = FactStatus.STALE, staleReason = verdict.reason).also(store::update)
                AnchorChecker.Verdict.Standing -> if (fact.status == FactStatus.STALE) fact.copy(status = FactStatus.ACTIVE, staleReason = null).also(store::update) else fact
                AnchorChecker.Verdict.Unknown -> fact.also { unreachable++ }
            }
        }
        return checked to unreachable
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 30

        // Facts an anchor search narrows down in SQL before its exact check; it takes the limit only afterwards.
        private const val ANCHOR_SCAN = 2000
    }
}
