package codeloupe.tools

import codeloupe.query.SymbolQuery
import codeloupe.query.View

object SymbolTool : ViewTool {
    override val name = "symbol"
    override val description = "Source of one declaration by Type.member, member(ParamType) for an overload, pkg.Type or path/File.kt:line. " +
        "Types over 120 lines give header and members unless full=true."
    override val properties = Schema.properties(
        "name" to Schema.string(),
        "full" to Schema.boolean("Whole body of large types"),
        "all" to Schema.boolean("Every match, not a list of ambiguous ones"),
    )
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String = SymbolQuery.run(
        view,
        SymbolQuery.Args(name = args.string("name"), full = args.bool("full") ?: false, all = args.bool("all") ?: false, limit = args.int("limit") ?: 10),
    )
}
