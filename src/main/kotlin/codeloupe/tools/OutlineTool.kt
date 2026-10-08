package codeloupe.tools

import codeloupe.query.OutlineQuery
import codeloupe.query.RepoMap
import codeloupe.query.View

object OutlineTool : ViewTool {
    override val name = "outline"
    override val description = "Members of a file or a type with line ranges and signatures, no bodies. Read this before reading a file; " +
        "then fetch only the members you need with `symbol`. Without target: a map of the repository's types, files ranked by how " +
        "much the rest of the code refers to them, within a token budget; focus (files or symbols) ranks their neighbourhood first. " +
        "Start with the map in a repository you do not know."
    override val properties = Schema.properties(
        "target" to Schema.string("File path (or unique suffix like \"shop/OrderService.kt\") or type name (Type, pkg.Type, Outer.Inner); omit for the repository map"),
        "focus" to Schema.strings("Map only: files or symbols the map is centred on, e.g. [\"shop/OrderService.kt\", \"Registry\"]"),
        "budget" to Schema.integer(100, 8000),
        "test" to Schema.boolean("Map only: true = include test sources (default: only when a focus is in them)"),
    )
    override val required = emptyList<String>()

    override fun run(view: View, args: ToolArgs): String {
        val target = args.string("target")
        if (!target.isNullOrBlank()) return OutlineQuery.run(view, target)
        return RepoMap.run(view, RepoMap.Args(focus = args.strings("focus"), budget = args.int("budget") ?: RepoMap.DEFAULT_BUDGET, test = args.bool("test")))
    }
}
