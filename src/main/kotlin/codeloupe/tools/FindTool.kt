package codeloupe.tools

import codeloupe.query.FindQuery
import codeloupe.query.View

object FindTool : ViewTool {
    override val name = "find"
    override val description = "Find declarations by name, Type.member or glob (*Routes); mode=search ranks them for words. Use instead of grep/rg."
    override val properties = Schema.properties(
        "q" to Schema.string("Name, Type.member or glob (* ?); words with mode=search"),
        "mode" to Schema.enum(listOf("name", "search")),
        "kind" to Schema.enum(
            listOf("class", "interface", "object", "enum", "companion", "annotation", "fun", "property", "constructor", "enum_entry", "typealias"),
        ),
        "module" to Schema.string("Module path prefix"),
        "test" to Schema.boolean("true = tests only, false = no tests"),
        "limit" to Schema.integer(1, 200),
    )
    override val required = listOf("q")

    override fun run(view: View, args: ToolArgs): String = FindQuery.run(
        view,
        FindQuery.Args(
            q = args.string("q"), kind = args.string("kind"), module = args.string("module"), test = args.bool("test"),
            locals = args.bool("locals") ?: false, limit = args.int("limit") ?: 30,
        ),
    )
}
