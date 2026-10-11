package codeloupe.hooks.check

import codeloupe.query.DeclRow
import codeloupe.query.RefRow
import codeloupe.query.View
import codeloupe.query.usages.Kinds
import codeloupe.query.usages.UsageFinder

/**
 * Names of one edited Kotlin file that the index cannot place, from the references the index holds for it (so the edit is already in
 * the view's overlay). Two findings, both chosen to stay silent when in doubt:
 *
 * - an unqualified call or type that the file cannot see: either nothing in the index declares it, the file does not import it, no other
 *   file mentions it and Kotlin does not provide it (a misspelt or invented name, not a library name used for the first time), or it is declared
 *   only as a top-level type or function of another package that the file does not import. A file with a star import of a package the index
 *   does not know is skipped, as is a name inside a class with supertypes outside the index, an extension, or a lambda with a receiver;
 * - a call whose every candidate is a function of the index that takes another number of arguments.
 */
internal class NameCheck(private val view: View) {
    private val finder = UsageFinder(view)
    private val cache = finder.cache

    fun check(path: String): List<Finding> {
        val file = cache.file(path) ?: return emptyList()
        val refs = view.refs("f.path = :path", mapOf("path" to path)).filter { it.path == path }.sortedWith(compareBy({ it.line }, { it.col }))
        val unknownAllowed = file.imports.none { it.star && !declaresPackage(it.fqn) }
        val found = LinkedHashMap<Triple<Int, Int, String>, Finding>()
        for (ref in refs) {
            val finding = judge(ref, path, unknownAllowed, file.imports.any { !it.star && (it.alias ?: it.fqn.substringAfterLast('.')) == ref.name }) ?: continue
            found.putIfAbsent(Triple(ref.line, ref.col, ref.name), finding)
        }
        return found.values.toList()
    }

    private fun judge(ref: RefRow, path: String, unknownAllowed: Boolean, imported: Boolean): Finding? {
        // A `name` is a property, a parameter or an object; only a capitalised one can be a type the file needs an import for.
        if (ref.kind != "call" && ref.kind != "type" && !(ref.kind == "name" && ref.name[0].isUpperCase())) return null
        if (ref.bind != null || StdlibNames.contains(ref.name)) return null
        val resolution = finder.resolve(ref)
        if (resolution.decls.isEmpty()) return if (ref.recv == null && unknownAllowed && !imported) unknown(ref, path) else null
        // A lambda's receiver or an extension's may declare a function of that name, with parameters the index does not know.
        if (ref.recv == null && (ref.recvType != null || receiverMayDeclare(ref, path))) return null
        return arity(ref, resolution.complete, resolution.decls, resolution.further)
    }

    private fun unknown(ref: RefRow, path: String): Finding? {
        if (Kinds.isTypeParameter(ref.name)) return null
        // The first segment of a qualified type (`com` in `com.sun.Foo`) is a package.
        if (ref.kind != "call" && !ref.name[0].isUpperCase()) return null
        if (receiverMayDeclare(ref, path)) return null
        val declared = cache.named(ref.name)
        if (declared.isNotEmpty()) return unimported(ref, path, declared)
        if (cache.refsNamed(ref.name).any { it.path != path }) return null
        val what = if (ref.kind == "call") "function" else "type"
        return Finding(ref.line, ref.col, "unknown $what `${ref.name}`: nothing in the index declares it and the file does not import it")
    }

    // Declared in other packages only: the code needs an import (or the name is a member of something it is not inside of).
    private fun unimported(ref: RefRow, path: String, declared: List<DeclRow>): Finding? {
        val reachable = declared.filter { !it.local && it.container.isEmpty() && it.receiver == null && Kinds.accessible(it, path) && (it.kind in Kinds.TYPES || it.kind == "fun") }
        if (reachable.isEmpty() || reachable.size != declared.size) return null
        val where = reachable.take(2).joinToString(" or ") { "${it.fqn} (${it.path.substringAfterLast('/')}:${it.declLine})" }
        return Finding(ref.line, ref.col, "`${ref.name}` is not imported: it is declared as $where")
    }

    // An unqualified name may be a member of what the code is inside of: a class with library supertypes, or an extension of a type the index lacks.
    private fun receiverMayDeclare(ref: RefRow, path: String): Boolean {
        val file = cache.file(path) ?: return true
        return file.chain(file.decl(ref.declId)).any {
            it.receiver != null || it.kind in Kinds.CLASSIFIERS && finder.context.types.closure(it).external.isNotEmpty()
        }
    }

    private fun arity(ref: RefRow, complete: Boolean, decls: List<DeclRow>, further: List<DeclRow>): Finding? {
        val args = ref.args?.takeIf { it >= 0 && ref.kind == "call" } ?: return null
        if (!complete || further.isNotEmpty() || decls.any { it.kind != "fun" } || !decls.all { knownCallee(ref, it) && onReceiver(ref, it) }) return null
        val arguments = finder.context.arguments
        if (decls.any { arguments.fits(it, args) }) return null
        return Finding(ref.line, ref.col, "`${ref.name}` called with $args argument(s), but ${expected(decls)} (${where(decls.first())})")
    }

    // A function whose parameters the index knows completely: not an extension, and not a member of a type with library supertypes.
    private fun knownCallee(ref: RefRow, d: DeclRow): Boolean {
        if (StdlibNames.contains(ref.name) || d.receiver != null) return false
        val owner = cache.parent(d) ?: return d.container.isEmpty()
        return owner.kind in Kinds.CLASSIFIERS && finder.context.types.closure(owner).external.isEmpty()
    }

    // `x.f()` is judged only when the receiver is written as the type that declares `f`: a library receiver can have a `f` of its own.
    private fun onReceiver(ref: RefRow, d: DeclRow): Boolean {
        val written = ref.recv ?: return true
        val type = (ref.recvType?.takeIf { it.isNotBlank() } ?: written).removeSuffix("()").substringBefore('<').removeSuffix("?").substringAfterLast('.')
        return type.isNotEmpty() && type[0].isUpperCase() && type == cache.parent(d)?.name
    }

    private fun expected(decls: List<DeclRow>): String {
        val params = decls.map { cache.params(it) }
        val min = params.minOf { p -> p.count { !it.default && !it.vararg } }
        val max = if (params.any { p -> p.any { it.vararg } }) null else params.maxOf { it.size }
        val shape = when {
            max == null -> "at least $min"
            max == min -> "$min"
            else -> "$min to $max"
        }
        return "it takes $shape"
    }

    private fun where(d: DeclRow) = "${d.path.substringAfterLast('/')}:${d.declLine}"

    // A star import of a package some indexed declaration lives in brings in only what the index knows.
    private fun declaresPackage(fqn: String): Boolean =
        view.decls("d.fqn LIKE :prefix ESCAPE '\\'", mapOf("prefix" to fqn.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%"), tail = "LIMIT 1").isNotEmpty()
}
