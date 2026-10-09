package codeloupe.changes

import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.OutlineQuery
import codeloupe.query.RefRow
import codeloupe.query.usages.Label
import codeloupe.query.usages.UsageFinder

/**
 * The test classes that use a declaration, directly or through the code that uses it, up to [DEPTH] steps: a private
 * helper that only a public function calls is reached through the tests of that function.
 */
internal class TestReach(private val finder: UsageFinder) {
    /** [complete] is false when a name was too common to follow or the call's budget ran out: absence of tests then proves nothing. */
    class Reach(val tests: Set<TestClass>, val complete: Boolean)

    private val callers = Callers(finder)
    private var spent = 0

    /** The tests of [decl]; a removed declaration is followed by the references that still carry its name. */
    fun of(decl: DeclRow, removed: Boolean): Reach {
        if (isTest(decl.path)) testClass(decl.path, decl)?.let { return Reach(setOf(it), true) }
        // `hashCode` or `equals` is called from everywhere by name: what it means is the tests of the type that declares it.
        if (decl.name in OBJECT_MEMBERS || decl.kind == "constructor" || decl.kind == "init") {
            return parentOf(decl, removed)?.let { of(it, false) } ?: Reach(emptySet(), false)
        }
        val tests = LinkedHashSet<TestClass>()
        var complete = true
        val seen = HashSet<Pair<String, Long>>().apply { add(decl.src to decl.id) }
        var frontier = listOf(decl)
        for (depth in 0 until DEPTH) {
            val next = ArrayList<DeclRow>()
            for (d in frontier) {
                val refs = finder.cache.view.refCount(d.name, MAX_REFS + 1)
                if (refs > MAX_REFS || spent + refs > BUDGET) {
                    complete = false
                    continue
                }
                spent += refs
                for ((ref, owner) in sites(d, removed && depth == 0)) {
                    // A class in test sources without a test method is a helper: its users are followed like production code.
                    val test = if (isTest(ref.path)) testClass(ref.path, owner) else null
                    if (test != null) tests += test
                    else if (owner != null && seen.add(owner.src to owner.id)) next += owner
                }
            }
            if (next.size > FRONTIER) complete = false
            frontier = next.take(FRONTIER)
            if (frontier.isEmpty()) break
        }
        if (frontier.isNotEmpty()) complete = false
        return Reach(tests, complete)
    }

    /**
     * The declaration [decl] belongs to, in the files as they are now. A removed declaration comes from the merge-base index, whose
     * ids mean nothing in this one: its parent is found by the name of its container.
     */
    fun parentOf(decl: DeclRow, removed: Boolean): DeclRow? {
        if (!removed) return finder.cache.parent(decl)
        if (decl.container.isEmpty()) return null
        return finder.cache.file(decl.path)?.all().orEmpty().firstOrNull { !it.local && it.kind in OutlineQuery.TYPE_KINDS && "${it.container}.${it.name}".removePrefix(".") == decl.container }
    }

    /** The exact references; the unsure ones only when none is exact (a common name would otherwise drag in unrelated tests). */
    private fun sites(decl: DeclRow, removed: Boolean): List<Pair<RefRow, DeclRow?>> {
        if (removed) return callers.removedSites(decl).map { it to owner(it) }
        val usages = finder.usages(listOf(decl)).filter { it.label != Label.OTHER && !isSelf(it.owner, decl) }
        val exact = usages.filter { it.label == Label.EXACT }
        return (exact.ifEmpty { usages }).map { it.ref to it.owner }
    }

    private fun isSelf(owner: DeclRow?, decl: DeclRow) = owner != null && owner.id == decl.id && owner.src == decl.src

    private fun owner(ref: RefRow): DeclRow? = finder.cache.owner(finder.cache.file(ref.path)?.decl(ref.declId))

    private fun testClass(path: String, owner: DeclRow?): TestClass? {
        val where = ModulePath.of(path)
        val pkg = finder.cache.file(path)?.packageName.orEmpty()
        val stem = path.substringAfterLast('/').substringBeforeLast('.')
        val top = when {
            owner == null -> stem + "Kt"
            owner.container.isNotEmpty() -> owner.container.substringBefore('.')
            owner.kind in OutlineQuery.TYPE_KINDS -> owner.name
            else -> stem + "Kt"
        }
        if (!hasTests(path, top)) return null
        val nested = owner != null && (owner.container.contains('.') || (owner.container.isNotEmpty() && owner.kind in OutlineQuery.TYPE_KINDS))
        return TestClass(where.module, where.sourceSet, if (pkg.isEmpty()) top else "$pkg.$top", nested)
    }

    private val withTests = HashMap<Pair<String, String>, Boolean>()

    /** Whether the top-level class [top] of the file holds a test method (JUnit, kotlin.test, TestNG annotations show in the signature). */
    private fun hasTests(path: String, top: String): Boolean = withTests.getOrPut(path to top) {
        finder.cache.file(path)?.all().orEmpty().any { d ->
            (d.container == top || d.container.startsWith("$top.")) && TEST_ANNOTATION.containsMatchIn(d.modifiers + " " + d.sig)
        }
    }

    companion object {
        private val TEST_ANNOTATION = Regex("""@(?:[A-Za-z]+\.)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b""")
        private val OBJECT_MEMBERS = setOf("hashCode", "equals", "toString", "compareTo")
        const val DEPTH = 3
        const val FRONTIER = 40
        const val MAX_REFS = 2_000
        const val BUDGET = 20_000

        fun isTest(path: String): Boolean = ModulePath.of(path).sourceSet.contains("test", ignoreCase = true)
    }
}
