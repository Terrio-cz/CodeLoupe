package codeloupe.tools

import codeloupe.query.View
import codeloupe.query.usages.CallsQuery

object CallsTool : ViewTool {
    override val name = "calls"
    override val description = "Call tree of a declaration: its callers (default) or callees, up to depth 3, " +
        "each node path:line [Container] signature @call lines, marked = exact or ? candidate."
    override val properties = Schema.properties(
        "name" to Schema.string(),
        "direction" to Schema.enum(listOf("callers", "callees")),
        "depth" to Schema.integer(1, 3),
        "limit" to Schema.integer(1, 200),
    )
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String = CallsQuery.run(
        view,
        CallsQuery.Args(args.string("name"), callees = args.string("direction") == "callees", depth = args.int("depth") ?: 2, limit = args.int("limit") ?: 40),
    )
}
