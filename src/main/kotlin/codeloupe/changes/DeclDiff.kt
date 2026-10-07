package codeloupe.changes

import codeloupe.query.usages.Param

/**
 * Matches the declarations of two versions of one file. Declarations pair up by kind, container and name; within
 * such a group an unchanged text pairs first, then an equal signature (`~`, body changed), then the closest parameter
 * lists (`^`, e.g. an overload that changed its parameters); what is left was added (`+`) or removed (`-`).
 */
object DeclDiff {
    fun of(before: List<DeclVersion>, after: List<DeclVersion>): List<DeclChange> {
        val old = before.groupBy(::identity)
        val new = after.groupBy(::identity)
        return LinkedHashSet(old.keys + new.keys).flatMap { group(old[it].orEmpty(), new[it].orEmpty()) }
            // A type's text holds its members: it changed by itself only when the text outside them did.
            .filterNot { it.mark == DeclChange.BODY && it.after!!.isType && it.before!!.ownText(before) == it.after.ownText(after) }
    }

    private fun group(before: List<DeclVersion>, after: List<DeclVersion>): List<DeclChange> {
        val old = before.toMutableList()
        val new = after.toMutableList()
        val changes = ArrayList<DeclChange>()
        pair(old, new) { a, b -> a.sameText(b) }
        pair(old, new) { a, b -> a.row.sig == b.row.sig }.forEach { (a, b) -> changes += DeclChange(DeclChange.BODY, a, b) }
        for (decl in new.toList().sortedBy { it.row.startLine }) {
            val closest = old.minByOrNull { distance(it, decl) } ?: break
            old -= closest
            new -= decl
            changes += DeclChange(DeclChange.SIGNATURE, closest, decl)
        }
        new.forEach { changes += DeclChange(DeclChange.ADDED, null, it) }
        old.forEach { changes += DeclChange(DeclChange.REMOVED, it, null) }
        return changes
    }

    /** Removes and returns the pairs [same] accepts, first match in order. */
    private fun pair(old: MutableList<DeclVersion>, new: MutableList<DeclVersion>, same: (DeclVersion, DeclVersion) -> Boolean) =
        new.toList().mapNotNull { b ->
            val a = old.firstOrNull { same(it, b) } ?: return@mapNotNull null
            old -= a
            new -= b
            a to b
        }

    // Parameters the two lists do not share, by type: the nearest old overload of a changed one.
    private fun distance(a: DeclVersion, b: DeclVersion): Int {
        val x = Param.of(a.row).map { it.type }
        val y = Param.of(b.row).map { it.type }
        return x.size + y.size - 2 * x.zip(y).count { (p, q) -> p == q }
    }

    private fun identity(d: DeclVersion) = Triple(d.row.kind, d.row.container, d.row.name)
}
