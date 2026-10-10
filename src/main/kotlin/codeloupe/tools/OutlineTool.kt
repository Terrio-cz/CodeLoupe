package codeloupe.tools

import codeloupe.query.OutlineQuery
import codeloupe.query.RepoMap
import codeloupe.query.View

object OutlineTool : ViewTool {
    override val name = "outline"
    override val description = "Members of a file or type with line ranges and signatures, no bodies; read before a file, then fetch with `symbol`. " +
        "Without target: a ranked repository map within a token budget (focus ranks its neighbourhood first)."
    override val properties = Schema.properties(
        "target" to Schema.string("File (unique suffix ok) or type; omit for the map"),
        "focus" to Schema.strings("Map only: files or symbols to centre on"),
        "budget" to Schema.integer(100, 8000),
        "test" to Schema.boolean("Map only: include test sources"),
    )
    override val required = emptyList<String>()

    override fun run(view: View, args: ToolArgs): String {
        val target = args.string("target")
        if (!target.isNullOrBlank()) return OutlineQuery.run(view, target)
        return RepoMap.run(view, RepoMap.Args(focus = args.strings("focus"), budget = args.int("budget") ?: RepoMap.DEFAULT_BUDGET, test = args.bool("test")))
    }
}
