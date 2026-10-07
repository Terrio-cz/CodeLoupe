package codeloupe.tools

import codeloupe.query.View
import codeloupe.query.usages.UsagesQuery

object UsagesTool : ViewTool {
    override val name = "usages"
    override val description = "Every reference to a declaration (Type.member, member(ParamType), pkg.Type), grouped by file and " +
        "enclosing declaration, one code line each, marked = exact or ? candidate. Covers what rg -w would find in code; " +
        "use instead of grep for callers and readers."
    override val properties = Schema.properties(
        "name" to Schema.string(),
        "all" to Schema.boolean("Also list same-name references that resolve to other declarations (-)"),
        "limit" to Schema.integer(1, 500),
    )
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String =
        UsagesQuery.run(view, UsagesQuery.Args(args.string("name"), limit = args.int("limit") ?: 40, all = args.bool("all") ?: false))
}
