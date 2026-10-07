package codeloupe.changes

/**
 * Matches the declarations of two versions of one file. Declarations pair up by kind, container and name; within
 * such a group an equal signature means unchanged or body changed (`~`), the rest pair up in order as signature
 * changes (`^`, e.g. an overload that changed its parameters), and what is left was added (`+`) or removed (`-`).
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
        for (decl in after) {
            val same = old.firstOrNull { it.row.sig == decl.row.sig } ?: continue
            old -= same
            new -= decl
            if (same.normalizedText != decl.normalizedText) changes += DeclChange(DeclChange.BODY, same, decl)
        }
        while (old.isNotEmpty() && new.isNotEmpty()) changes += DeclChange(DeclChange.SIGNATURE, old.removeFirst(), new.removeFirst())
        new.forEach { changes += DeclChange(DeclChange.ADDED, null, it) }
        old.forEach { changes += DeclChange(DeclChange.REMOVED, it, null) }
        return changes
    }

    private fun identity(d: DeclVersion) = Triple(d.row.kind, d.row.container, d.row.name)
}
