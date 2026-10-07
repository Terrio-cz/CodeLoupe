package codeloupe.query.usages

import codeloupe.query.DeclRow

/** Override relations of members: same name and kind, same parameter count, in a supertype or a subtype. */
internal class Overrides(private val cache: IndexCache, private val types: Types) {
    /** Members [member] overrides, nearest first. */
    fun overridden(member: DeclRow): List<DeclRow> {
        val owner = ownerType(member) ?: return emptyList()
        return types.closure(owner).levels.drop(1).flatten().flatMap { t -> cache.children(t).filter { matches(it, member) } }
    }

    /** Members of subtypes that override [member]. */
    fun overriding(member: DeclRow): List<DeclRow> {
        val owner = ownerType(member) ?: return emptyList()
        return types.allSubtypes(owner).flatMap { t -> cache.children(t).filter { "override" in it.modifiers.split(' ') && matches(it, member) } }
    }

    private fun ownerType(member: DeclRow): DeclRow? =
        if (member.local || member.kind !in MEMBER_KINDS) null else cache.parent(member)?.takeIf { it.kind in Kinds.CLASSIFIERS }

    private fun matches(d: DeclRow, member: DeclRow) =
        d.name == member.name && d.kind == member.kind && (d.kind != "fun" || d.paramCount == member.paramCount)

    private companion object {
        val MEMBER_KINDS = setOf("fun", "property")
    }
}
