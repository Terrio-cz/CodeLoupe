package codeloupe.query.usages

import codeloupe.lang.TypeSpec
import codeloupe.query.DeclRow
import codeloupe.query.RefRow

/**
 * Resolves the [TypeSpec]s the extractor records for locals, receivers and undeclared types into type text and the
 * place its names are resolved from. Answers cut short by a cycle or the depth limit, or given while [settled] is
 * false, are not remembered: a later, full evaluation may differ.
 */
internal class TypeSpecs(
    private val cache: IndexCache,
    private val settled: () -> Boolean,
    private val resolveRef: (RefRow) -> Resolution,
) {
    /** Type text and the place its names are resolved from. */
    data class TypeText(val text: String, val file: FileScope, val at: DeclRow?)

    private val memo = HashMap<Triple<String, String, Long?>, TypeText?>()
    private val active = HashSet<Triple<String, String, Long?>>()
    private var cuts = 0

    fun text(spec: String, file: FileScope, at: DeclRow?): TypeText? {
        if (spec.isEmpty()) return null
        val key = Triple(file.path, spec, at?.id)
        if (key in memo) return memo[key]
        if (active.size >= MAX_DEPTH || !active.add(key)) {
            cuts++
            return null
        }
        val cutsBefore = cuts
        try {
            return compute(spec, file, at).also { if (cuts == cutsBefore && settled()) memo[key] = it }
        } finally {
            active.remove(key)
        }
    }

    /** The declared type of [d]: its return or property type (a spec when undeclared), the class itself. */
    fun declaredType(d: DeclRow): TypeText? {
        val file = cache.file(d.path) ?: return null
        if (d.kind in Kinds.CLASSIFIERS) return TypeText(d.fqn, file, null)
        return d.returns?.let { text(it, file, cache.parent(d)) }
    }

    private fun compute(spec: String, file: FileScope, at: DeclRow?): TypeText? = when (spec[0]) {
        TypeSpec.ELEMENT -> text(spec.substring(1), file, at)?.let { inner -> TypeSpec.elementType(inner.text)?.let { TypeText(it, inner.file, inner.at) } }
        TypeSpec.POSITION -> referenced(spec.substringBefore(TypeSpec.OR), file)
            ?: spec.substringAfter(TypeSpec.OR, "").let { if (it.isEmpty()) null else text(it, file, at) }
        else -> TypeText(spec, file, at)
    }

    /**
     * Only a reference that surely resolves gives a type; a guess would spread into every use of the local. A
     * capitalised call nothing in the index declares constructs a library type (`StringBuilder()`).
     */
    private fun referenced(position: String, file: FileScope): TypeText? {
        val (line, col) = position.substring(1).split(':').map(String::toInt)
        val ref = cache.refAt(file.path, line, col) ?: return null
        val resolution = resolveRef(ref).takeIf { it.complete } ?: return null
        if (resolution.decls.isEmpty() && ref.kind == "call" && ref.name.first().isUpperCase()) {
            return TypeText(listOfNotNull(ref.recv, ref.name).joinToString("."), file, null)
        }
        return resolution.decls.mapNotNull(::declaredType).distinctBy { it.text }.singleOrNull()
    }

    /** The receiver of the function type a lambda is passed as (`&@line:col#p`), from the called declaration. */
    fun lambdaReceiver(spec: String, file: FileScope): LambdaReceiver {
        val position = spec.substring(1).substringBefore(TypeSpec.ARGUMENT)
        val argument = spec.substringAfter(TypeSpec.ARGUMENT).substringBefore(TypeSpec.OR)
        val (line, col) = position.substring(1).split(':').map(String::toInt)
        val called = cache.refAt(file.path, line, col)?.let(resolveRef) ?: return LambdaReceiver.Unknown
        if (called.decls.isEmpty() && (called.complete || called.byName)) return LambdaReceiver.Library
        val callee = called.takeIf { it.complete }?.decls?.singleOrNull { it.kind == "fun" } ?: return LambdaReceiver.Unknown
        val params = cache.params(callee)
        val index = argument.toIntOrNull()
        val param = when {
            index == null -> params.firstOrNull { it.name == argument }
            index < 0 -> params.lastOrNull()
            else -> params.getOrNull(index)
        }
        val type = param?.type?.takeIf { "->" in it } ?: return LambdaReceiver.Unknown
        val receiver = FUNCTION_RECEIVER.find(type)?.groupValues?.get(1) ?: return LambdaReceiver.None
        return LambdaReceiver.Typed(TypeText(receiver, cache.file(callee.path) ?: return LambdaReceiver.Unknown, cache.parent(callee)))
    }

    private companion object {
        /** `R.() -> T`, `suspend R.(X) -> T`, `(R.() -> T)?` -> `R`. */
        val FUNCTION_RECEIVER = Regex("""^\(?\s*(?:suspend\s+)?([\w.<>?, *]+?)\.\(""")

        const val MAX_DEPTH = 6
    }
}
