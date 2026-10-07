package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.RefRow

/** One reference -> the declarations it may denote, from the file's scopes and the receiver's type. */
internal class RefResolver(
    private val cache: IndexCache,
    private val types: Types,
    private val lookup: MemberLookup,
    private val implicit: ImplicitScope,
    private val receivers: Receivers,
) {
    fun resolve(ref: RefRow): Resolution {
        val file = cache.file(ref.path) ?: return Resolution(emptyList(), complete = false)
        val chain = file.chain(file.decl(ref.declId))
        val accept = accepts(ref.kind) ?: return Resolution(emptyList(), complete = false)
        val resolution = when {
            ref.recv == null && ref.bind != null -> local(ref, chain)
            ref.recv == null -> implicit.find(ref.name, file, chain, accept, ref.recvType?.let { receivers.fromSpec(it, file, chain.firstOrNull()) })
            else -> qualified(ref, file, chain, accept)
        }
        return Arguments.narrow(preferCallables(resolution, ref.kind), ref.args).filter { accessible(it, ref.path) }
    }

    // A private declaration is out of reach outside its own file.
    private fun accessible(d: DeclRow, path: String) = d.path == path || "private" !in d.modifiers.split(' ')

    // Names bound inside code shadow everything else; only local declarations can be meant.
    private fun local(ref: RefRow, chain: List<DeclRow>): Resolution {
        val ids = chain.map { it.id }.toSet()
        return Resolution(cache.named(ref.name).filter { it.local && it.path == ref.path && it.parentId in ids }, complete = true)
    }

    private fun qualified(ref: RefRow, file: FileScope, chain: List<DeclRow>, accept: (DeclRow) -> Boolean): Resolution =
        when (val type = receivers.of(ref, file, chain)) {
            is ReceiverType.Static -> type.types.map { lookup.static(it, ref.name, accept) }
                .let { r -> Resolution(r.flatMap { it.decls }, complete = true, further = r.flatMap { it.further }) }
            is ReceiverType.Instance -> {
                val members = type.types.distinct().map { lookup.instance(types.closure(it), ref.name, accept) }
                val decls = members.flatMap { it.decls }
                if (decls.isNotEmpty()) Resolution(decls, complete = true, further = members.flatMap { it.further })
                else Resolution(lookup.extensions(ref.name, type.names, file, accept), complete = true)
            }
            is ReceiverType.Package -> Resolution(cache.named(ref.name).filter { !it.local && it.fqn == "${type.name}.${ref.name}" && accept(it) }, complete = true)
            ReceiverType.Unknown -> Resolution(cache.named(ref.name).filter { Kinds.isMember(it) && accept(it) }, complete = false)
        }

    // `x()` on a property means `invoke`: only when no function or class of that name is in reach.
    private fun preferCallables(r: Resolution, kind: String): Resolution {
        if (kind != "call" || r.decls.none { it.kind != "property" }) return r
        return r.filter { it.kind != "property" }
    }

    private fun accepts(kind: String): ((DeclRow) -> Boolean)? = when (kind) {
        "type" -> { d -> d.kind in Kinds.TYPES }
        "call" -> { d -> d.kind == "fun" || d.kind == "property" || d.kind in Kinds.CLASSIFIERS }
        "name" -> { d -> d.kind == "property" || d.kind in Kinds.CLASSIFIERS }
        "nav", "callable_ref" -> { d -> d.kind == "fun" || d.kind == "property" || d.kind in Kinds.CLASSIFIERS }
        else -> null
    }
}
