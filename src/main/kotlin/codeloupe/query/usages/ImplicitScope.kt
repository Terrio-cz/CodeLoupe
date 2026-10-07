package codeloupe.query.usages

import codeloupe.query.DeclMatch
import codeloupe.query.DeclRow

/**
 * An unqualified name: members of the enclosing classes and extension receivers (innermost first), then
 * top-level declarations the file sees. A name none of them declares may still be a member of a lambda's
 * receiver (`with(x) { m() }`, DSL builders), so that answer is incomplete.
 */
internal class ImplicitScope(
    private val cache: IndexCache,
    private val types: Types,
    private val visibility: Visibility,
    private val lookup: MemberLookup,
) {
    fun find(name: String, file: FileScope, chain: List<DeclRow>, accept: (DeclRow) -> Boolean, lambdaReceiver: ReceiverType? = null): Resolution {
        when (lambdaReceiver) {
            is ReceiverType.Instance -> onInstance(lambdaReceiver, name, file, accept)?.let { return it }
            // An implicit receiver of unknown type may declare the name itself.
            ReceiverType.Unknown -> return lexical(name, file, chain, accept).copy(complete = false)
            else -> Unit
        }
        return lexical(name, file, chain, accept)
    }

    private fun onInstance(receiver: ReceiverType.Instance, name: String, file: FileScope, accept: (DeclRow) -> Boolean): Resolution? {
        val members = receiver.types.map { lookup.instance(types.closure(it), name, accept) }
        val found = members.flatMap { it.decls }
        if (found.isNotEmpty()) return Resolution(found, complete = true, further = members.flatMap { it.further })
        return lookup.extensions(name, receiver.names, file, accept).takeIf { it.isNotEmpty() }?.let { Resolution(it, complete = true) }
    }

    private fun lexical(name: String, file: FileScope, chain: List<DeclRow>, accept: (DeclRow) -> Boolean): Resolution {
        for (outer in chain) {
            val receivers = receiverTypes(outer, file) ?: continue
            val members = receivers.types.map { lookup.instance(types.closure(it), name, accept) }
            val statics = if (outer.kind in Kinds.CLASSIFIERS) lookup.static(outer, name, accept) else Resolution.NOTHING
            val found = members.flatMap { it.decls } + statics.decls
            if (found.isNotEmpty()) return Resolution(found, complete = true, further = members.flatMap { it.further } + statics.further)
            val extensions = lookup.extensions(name, receivers.names, file, accept)
            if (extensions.isNotEmpty()) return Resolution(extensions, complete = true)
        }
        val top = visibility.topLevel(name, file).filter(accept)
        if (top.isNotEmpty()) return Resolution(top, complete = true)
        return Resolution(cache.named(name).filter { Kinds.isMember(it) && accept(it) }, complete = false)
    }

    /** The implicit `this` a declaration brings into its body: its class, or the receiver of an extension. */
    fun receiverTypes(outer: DeclRow, file: FileScope): ReceiverType.Instance? = when {
        outer.kind in Kinds.CLASSIFIERS -> instanceOf(listOf(outer), emptySet())
        outer.receiver != null -> {
            val resolved = types.resolve(outer.receiver, file, cache.parent(outer))
            instanceOf(resolved, setOf(DeclMatch.baseType(outer.receiver).substringAfterLast(' ').substringAfterLast('.')))
        }
        else -> null
    }

    fun instanceOf(resolved: List<DeclRow>, names: Set<String>): ReceiverType.Instance =
        ReceiverType.Instance(resolved, names + resolved.flatMap { types.closure(it).names })
}
