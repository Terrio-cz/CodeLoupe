package codeloupe.query.usages

import codeloupe.query.DeclMatch
import codeloupe.query.DeclRow

/**
 * An unqualified name: a lambda's implicit receiver, members of the enclosing classes and extension receivers
 * (innermost first), then top-level declarations the file sees. Any receiver on the way whose type or supertypes lie
 * outside the index may declare the name itself when libraries declare it at all, so such an answer is incomplete.
 */
internal class ImplicitScope(
    private val cache: IndexCache,
    private val types: Types,
    private val visibility: Visibility,
    private val lookup: MemberLookup,
    private val libraryNames: LibraryNames,
) {
    fun find(name: String, file: FileScope, chain: List<DeclRow>, accept: (DeclRow) -> Boolean, lambdaReceiver: ReceiverType? = null): Resolution =
        when (lambdaReceiver) {
            is ReceiverType.Instance -> onInstance(lambdaReceiver, name, file, accept)?.let { if (lambdaReceiver.open) it.openToLibrary() else it }
                ?: lexical(name, file, chain, accept).let { if (opensToLibrary(lambdaReceiver, name)) it.openToLibrary() else it }
            // A receiver of unknown type may declare the name: every indexed member of that name is in play.
            ReceiverType.Unknown -> lexical(name, file, chain, accept).let { r ->
                Resolution.byName((r.decls + cache.named(name).filter { Kinds.isMember(it) && accept(it) }).distinct())
            }
            else -> lexical(name, file, chain, accept)
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

    // Enums extend `Enum` and `Comparable` without saying so.
    fun instanceOf(resolved: List<DeclRow>, names: Set<String>): ReceiverType.Instance =
        ReceiverType.Instance(resolved, names + resolved.flatMap { types.closure(it).names + if (isEnum(it)) ENUM_SUPERTYPES else emptySet() })

    /** A receiver typed outside the index, or with supertypes outside it (enums extend `Enum`), has members nobody knows. */
    fun hasLibraryPart(receiver: ReceiverType.Instance) =
        receiver.open || receiver.types.isEmpty() || receiver.types.any { isEnum(it) || types.closure(it).external.isNotEmpty() }

    // Such a receiver may declare [name] itself — when libraries declare that name at all.
    private fun opensToLibrary(receiver: ReceiverType.Instance, name: String) = hasLibraryPart(receiver) && libraryNames.contains(name)

    private fun isEnum(d: DeclRow) = d.kind == "enum" || d.kind == "enum_entry"

    private fun onInstance(receiver: ReceiverType.Instance, name: String, file: FileScope, accept: (DeclRow) -> Boolean): Resolution? {
        val members = receiver.types.map { lookup.instance(types.closure(it), name, accept) }
        val found = members.flatMap { it.decls }.distinct()
        if (found.isNotEmpty()) return Resolution(found, complete = true, further = members.flatMap { it.further })
        return lookup.extensions(name, receiver.names, file, accept).takeIf { it.isNotEmpty() }?.let { Resolution(it, complete = true) }
    }

    private fun lexical(name: String, file: FileScope, chain: List<DeclRow>, accept: (DeclRow) -> Boolean): Resolution {
        var open = false
        for (outer in chain) {
            val receiver = receiverTypes(outer, file) ?: continue
            val members = receiver.types.map { lookup.instance(types.closure(it), name, accept) }
            val statics = if (outer.kind in Kinds.CLASSIFIERS) lookup.static(outer, name, accept) else Resolution.NOTHING
            // An object's members are both its instance and its static members.
            val found = (members.flatMap { it.decls } + statics.decls).distinct()
            val answer = when {
                found.isNotEmpty() -> Resolution(found, complete = true, further = members.flatMap { it.further } + statics.further)
                else -> lookup.extensions(name, receiver.names, file, accept).takeIf { it.isNotEmpty() }?.let { Resolution(it, complete = true) }
            }
            if (answer != null) return if (open) answer.openToLibrary() else answer
            open = open || opensToLibrary(receiver, name)
        }
        val top = visibility.topLevel(name, file, accept)
        if (top.isNotEmpty()) return Resolution(top, complete = !open, byName = open)
        return Resolution.byName(cache.named(name).filter { Kinds.isMember(it) && accept(it) })
    }

    private fun Resolution.openToLibrary() = copy(complete = false, byName = true)

    private companion object {
        val ENUM_SUPERTYPES = setOf("Enum", "Comparable")
    }
}
