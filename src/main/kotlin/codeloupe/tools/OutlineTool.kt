package codeloupe.tools

import codeloupe.query.OutlineQuery
import codeloupe.query.View

object OutlineTool : Tool {
    override val name = "outline"
    override val description = "Members of a file or a type with line ranges and signatures, no bodies. Read this before reading a file; " +
        "then fetch only the members you need with `symbol`."
    override val properties = Schema.properties(
        "target" to Schema.string("File path (or unique suffix like \"shop/OrderService.kt\") or type name (Type, pkg.Type, Outer.Inner)"),
    )
    override val required = listOf("target")

    override fun run(view: View, args: ToolArgs): String = OutlineQuery.run(view, args.string("target"))
}
