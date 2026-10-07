package codeloupe.query.usages

import codeloupe.lang.RefFact
import codeloupe.query.DeclMatch
import codeloupe.query.DeclRow
import codeloupe.query.RefRow

/** One reference -> the declarations it may denote, from the file's scopes and the receiver's type. */
internal class RefResolver(
    private val context: IndexContext,
    private val implicit: ImplicitScope,
    private val receivers: Receivers,
) {
    private val cache = context.cache
    private val types = context.types
    private val lookup = context.lookup

    fun resolve(ref: RefRow): Resolution {
        val file = cache.file(ref.path) ?: return Resolution(emptyList(), complete = false)
        val chain = file.chain(file.decl(ref.declId))
        val kind = accepts(ref.kind) ?: return Resolution(emptyList(), complete = false)
        val accept = { d: DeclRow -> kind(d) && Kinds.accessible(d, ref.path) }
        val resolution = when {
            ref.kind == "type" && ref.recv != null ->
                Resolution(types.resolve("${ref.recv}.${ref.name}", file, chain.firstOrNull(), keepAliases = true), complete = true)
            ref.recv == null && ref.bind != null -> bound(ref, file, chain, accept)
            ref.recv == null -> lexical(ref, file, chain, accept)
            else -> qualified(ref, file, chain, accept)
        }
        return if (ref.kind == "call") context.arguments.narrow(resolution, ref.args) else resolution
    }

    private fun lexical(ref: RefRow, file: FileScope, chain: List<DeclRow>, accept: (DeclRow) -> Boolean): Resolution =
        implicit.find(ref.name, file, chain, accept, ref.recvType?.let { receivers.lambdaReceiver(it, file, chain.firstOrNull()) })

    /**
     * A name bound in code: its local declarations. A call goes past a binding that cannot be invoked (`flag()` with
     * `flag: Boolean` calls a function); one of a type that may have `invoke` (a `fun interface`, an alias) leaves
     * both open. A binding across a class body may also be that class's member.
     */
    private fun bound(ref: RefRow, file: FileScope, chain: List<DeclRow>, accept: (DeclRow) -> Boolean): Resolution {
        val bind = ref.bind.orEmpty()
        val beyondClass = bind.startsWith(RefFact.BEYOND_CLASS)
        val type = bind.removePrefix(RefFact.BEYOND_CLASS)
        val ids = chain.map { it.id }.toSet()
        val locals = cache.named(ref.name).filter { it.local && it.path == ref.path && it.parentId in ids }
        val localFunctions = locals.filter { it.kind == "fun" }
        return when {
            ref.kind == "call" || ref.kind == "callable_ref" -> when {
                localFunctions.isNotEmpty() -> Resolution(localFunctions, complete = !beyondClass)
                ref.kind == "callable_ref" -> lexical(ref, file, chain, accept)
                "->" in type -> Resolution(locals, complete = !beyondClass)
                DeclMatch.baseType(type) in NOT_INVOCABLE -> lexical(ref, file, chain, accept)
                else -> lexical(ref, file, chain, accept).let { it.copy(decls = (it.decls + locals).distinct(), complete = false, byName = false) }
            }
            beyondClass -> Resolution((locals + cache.named(ref.name).filter { Kinds.isMember(it) && accept(it) }).distinct(), complete = false)
            else -> Resolution(locals, complete = true)
        }
    }

    private fun qualified(ref: RefRow, file: FileScope, chain: List<DeclRow>, accept: (DeclRow) -> Boolean): Resolution =
        when (val type = receivers.of(ref, file, chain)) {
            is ReceiverType.Static -> static(ref, type, file, accept)
            is ReceiverType.Instance -> instance(ref.name, type, file, accept)
            is ReceiverType.Package -> Resolution(cache.named(ref.name).filter { !it.local && it.fqn == "${type.name}.${ref.name}" && accept(it) }, complete = true)
            ReceiverType.Unknown -> Resolution.byName(cache.named(ref.name).filter { Kinds.isMember(it) && accept(it) })
        }

    // `Type::member` names an instance member (or extension) too.
    private fun static(ref: RefRow, type: ReceiverType.Static, file: FileScope, accept: (DeclRow) -> Boolean): Resolution {
        val found = type.types.map { lookup.static(it, ref.name, accept) }
        val statics = Resolution(found.flatMap { it.decls }, complete = true, further = found.flatMap { it.further })
        if (ref.kind != "callable_ref" || statics.decls.isNotEmpty()) return statics
        return instance(ref.name, implicit.instanceOf(type.types, type.types.map { it.name }.toSet()), file, accept)
    }

    /**
     * Members and extensions of an instance. Past them, a type with supertypes outside the index may meet an extension
     * declared on a library type (`fun Throwable.f()` on an `IOException` or a `MyEx : RuntimeException`), and an
     * indexed type a subtype's member through a smart cast (`if (e is Sub) e.f()`) — both only candidates.
     */
    private fun instance(name: String, type: ReceiverType.Instance, file: FileScope, accept: (DeclRow) -> Boolean): Resolution {
        val members = type.types.distinct().map { lookup.instance(types.closure(it), name, accept) }
        val decls = members.flatMap { it.decls }
        if (decls.isNotEmpty()) return Resolution(decls, complete = true, further = members.flatMap { it.further })
        lookup.extensions(name, type.names, file, accept).let { if (it.isNotEmpty()) return Resolution(it, complete = true) }
        val library = if (implicit.opensToLibrary(type)) lookup.onLibraryTypes(name, file, accept) else emptyList()
        val cast = type.types.flatMap(types::allSubtypes).flatMap { sub -> lookup.instance(types.closure(sub), name, accept).decls }
        val guesses = (library + cast).distinct()
        return Resolution(guesses, complete = guesses.isEmpty())
    }

    private fun accepts(kind: String): ((DeclRow) -> Boolean)? = when (kind) {
        "type" -> { d -> d.kind in Kinds.TYPES }
        "call" -> { d -> d.kind == "fun" || d.kind == "property" || d.kind in Kinds.CLASSIFIERS }
        "name" -> { d -> d.kind == "property" || d.kind in Kinds.CLASSIFIERS }
        "nav", "callable_ref" -> { d -> d.kind == "fun" || d.kind == "property" || d.kind in Kinds.CLASSIFIERS }
        else -> null
    }

    private companion object {
        /** Built-in value types without `invoke`: calling a binding of one of them calls a function of that name. */
        val NOT_INVOCABLE = setOf("Boolean", "Int", "Long", "Short", "Byte", "Double", "Float", "Char", "String", "Unit")
    }
}
