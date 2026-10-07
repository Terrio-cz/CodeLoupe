package codeloupe.tools

import codeloupe.query.FindQuery
import codeloupe.query.View

object FindTool : Tool {
    override val name = "find"
    override val description = "Find declarations (classes, functions, properties, …) by name, qualified name (Type.member) or glob " +
        "(*Routes). One line per hit: path:lines [container] signature. Use instead of grep/rg to locate code."
    override val properties = Schema.properties(
        "q" to Schema.string("Name, Type.member, package.Type or glob with * ?"),
        "kind" to Schema.enum(
            listOf("class", "interface", "object", "enum", "companion", "annotation", "fun", "property", "constructor", "enum_entry", "typealias"),
        ),
        "module" to Schema.string("Module path prefix, e.g. \"public-api\" or \"importers/ruian\""),
        "test" to Schema.boolean("true = only test sources, false = exclude them"),
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
