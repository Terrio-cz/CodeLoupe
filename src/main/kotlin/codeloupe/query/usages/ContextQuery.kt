package codeloupe.query.usages

import codeloupe.query.Members
import codeloupe.query.Resolver
import codeloupe.query.SymbolQuery
import codeloupe.query.View

/** `context`: a declaration's source, its callers and its callees in one answer, instead of three calls. */
object ContextQuery {
    data class Args(val name: String?, val full: Boolean = false, val limit: Int = 20)

    fun run(view: View, args: Args): String {
        val name = args.name.orEmpty()
        val targets = Resolver.resolve(view, name)
        if (targets.isEmpty()) return "no declaration \"$name\"" + Members.suggest(view, name)
        TargetLines.ambiguity(name, targets)?.let { return it }
        val source = SymbolQuery.run(view, SymbolQuery.Args(name, full = args.full))
        val callers = CallsQuery.run(view, CallsQuery.Args(name, callees = false, depth = 1, limit = args.limit))
        val callees = CallsQuery.run(view, CallsQuery.Args(name, callees = true, depth = 1, limit = args.limit))
        return listOf(source, callers, callees).joinToString("\n\n")
    }
}
