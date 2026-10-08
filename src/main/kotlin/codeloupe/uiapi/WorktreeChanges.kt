package codeloupe.uiapi

import codeloupe.changes.ChangeSet
import codeloupe.changes.DeclChange
import codeloupe.changes.DeclDiff
import codeloupe.changes.DeclVersion
import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.View
import codeloupe.query.usages.Label
import codeloupe.query.usages.UsageFinder

/**
 * The structured form of what `changes` answers as text: declarations changed against the merge-base with their callers
 * and the tests that touch or call them. Bounded like the tool: a name with too many references is not resolved.
 */
internal object WorktreeChanges {
    private const val MAX_CHANGES = 200
    private const val MAX_CALLERS = 100
    private const val MAX_TESTS = 100
    private const val MAX_REFS = 2_000
    private const val BUDGET_REFS = 20_000

    fun report(set: ChangeSet, after: View, before: View?): ChangeReport {
        val finder = UsageFinder(after)
        val changes = ArrayList<WorktreeDetail.Change>()
        val callers = ArrayList<WorktreeDetail.Caller>()
        val tests = LinkedHashMap<String, WorktreeDetail.TestRef>()
        var spent = 0
        for (file in set.files.sortedBy { it.path }) {
            if (isTest(file.path)) tests["${file.path}|"] = WorktreeDetail.TestRef(file.path, null, "touched")
            val old = if (file.status == 'A' || before == null) emptyList() else versions(before, file.path)
            val new = if (file.status == 'D') emptyList() else versions(after, file.path)
            val diff = DeclDiff.of(old, new).sortedBy { it.current.row.startLine }
            // The members of an added (removed) type are part of it, not news of their own.
            val whole = diff.filter { it.mark == DeclChange.ADDED || it.mark == DeclChange.REMOVED }.mapTo(HashSet()) { it.mark to it.current.row.id }
            for (change in diff) {
                val row = change.current.row
                if ((change.mark == DeclChange.ADDED || change.mark == DeclChange.REMOVED) && row.parentId != null && (change.mark to row.parentId) in whole) continue
                if (changes.size >= MAX_CHANGES) break
                var count = 0
                if (change.mark != DeclChange.ADDED && change.mark != DeclChange.REMOVED) {
                    val refs = finder.cache.view.refCount(row.name, MAX_REFS + 1)
                    if (refs <= MAX_REFS && spent + refs <= BUDGET_REFS) {
                        spent += refs
                        for (usage in finder.usages(listOf(row))) {
                            if (usage.label == Label.OTHER || sameDecl(usage.owner, row)) continue
                            count++
                            val owner = usage.owner
                            if (isTest(usage.ref.path)) {
                                if (tests.size < MAX_TESTS) tests.putIfAbsent("${usage.ref.path}|${owner?.fqn}", WorktreeDetail.TestRef(usage.ref.path, owner?.fqn, "calls_changed"))
                            } else if (callers.size < MAX_CALLERS) {
                                callers += WorktreeDetail.Caller(owner?.fqn ?: "${usage.ref.path}:${usage.ref.line}", usage.ref.path, usage.ref.line, row.fqn, usage.label == Label.EXACT)
                            }
                        }
                    }
                }
                changes += WorktreeDetail.Change(kindOf(change.mark), row.kind, row.fqn, row.path, row.declLine.takeIf { it > 0 }, count)
            }
        }
        return ChangeReport(set.defaultRef, set.mergeBase, changes, callers, tests.values.toList(), set.files.size + set.otherFiles.size)
    }

    private fun kindOf(mark: Char) = when (mark) {
        DeclChange.ADDED -> WorktreeDetail.ChangeKind.ADDED
        DeclChange.SIGNATURE -> WorktreeDetail.ChangeKind.SIGNATURE
        DeclChange.REMOVED -> WorktreeDetail.ChangeKind.REMOVED
        else -> WorktreeDetail.ChangeKind.BODY
    }

    private fun versions(view: View, path: String): List<DeclVersion> {
        val content = view.file(path)?.content ?: return emptyList()
        return DeclVersion.of(view.decls("f.path = :path", mapOf("path" to path), "ORDER BY start_line"), content)
    }

    private fun isTest(path: String) = ModulePath.of(path).sourceSet.contains("test", ignoreCase = true)

    private fun sameDecl(owner: DeclRow?, decl: DeclRow) = owner != null && owner.id == decl.id && owner.src == decl.src
}
