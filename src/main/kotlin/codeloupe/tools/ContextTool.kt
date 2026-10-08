package codeloupe.tools

import codeloupe.query.View
import codeloupe.query.usages.ContextQuery

object ContextTool : ViewTool {
    override val name = "context"
    override val description = "Everything to read before changing a declaration, in one call: its source (as symbol), its direct callers " +
        "and the declarations it calls (as calls, depth 1). Name it like symbol: Type.member, member(ParamType), pkg.Type."
    override val properties = Schema.properties(
        "name" to Schema.string(),
        "full" to Schema.boolean("Whole body even for large types"),
        "limit" to Schema.integer(1, 200),
    )
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String =
        ContextQuery.run(view, ContextQuery.Args(args.string("name"), full = args.bool("full") ?: false, limit = args.int("limit") ?: 20))
}
