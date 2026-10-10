package codeloupe.tools

import codeloupe.query.View
import codeloupe.query.usages.UsagesQuery

object UsagesTool : ViewTool {
    override val name = "usages"
    override val description = "References to a declaration by file and enclosing declaration, one code line each, = exact or ? candidate; covers rg -w. " +
        "Use instead of grep for callers and readers."
    override val properties = Schema.properties(
        "name" to Schema.string(),
        "all" to Schema.boolean("Also same-name references that resolve elsewhere (-)"),
        "limit" to Schema.integer(1, 500),
    )
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String =
        UsagesQuery.run(view, UsagesQuery.Args(args.string("name"), limit = args.int("limit") ?: 40, all = args.bool("all") ?: false))
}
