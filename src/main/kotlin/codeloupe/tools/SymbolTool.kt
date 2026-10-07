package codeloupe.tools

import codeloupe.query.SymbolQuery
import codeloupe.query.View

object SymbolTool : ViewTool {
    override val name = "symbol"
    override val description = "Source of one declaration — KDoc, annotations and body — by name: Type.member, member(ParamType, …) " +
        "for an overload, pkg.Type, or path/File.kt:line. Types over 120 lines return their header and member list unless full=true. " +
        "Each result carries hash= for later edits."
    override val properties = Schema.properties(
        "name" to Schema.string(),
        "full" to Schema.boolean("Whole body even for large types"),
        "all" to Schema.boolean("Return every match instead of listing ambiguous ones"),
    )
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String = SymbolQuery.run(
        view,
        SymbolQuery.Args(name = args.string("name"), full = args.bool("full") ?: false, all = args.bool("all") ?: false, limit = args.int("limit") ?: 10),
    )
}
