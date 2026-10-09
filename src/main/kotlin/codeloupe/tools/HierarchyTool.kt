package codeloupe.tools

import codeloupe.query.View
import codeloupe.query.usages.HierarchyQuery

object HierarchyTool : ViewTool {
    override val name = "hierarchy"
    override val description = "Direct subtypes (object expressions included) of a type, and lambdas converted to a fun " +
        "interface; or what a member overrides and what overrides it. supers=true adds direct supertypes, deep=true transitive links."
    override val properties = Schema.properties(
        "name" to Schema.string("Type, pkg.Type or Type.member"),
        "supers" to Schema.boolean("Also the direct supertypes"),
        "deep" to Schema.boolean("Supertypes and subtypes, transitively"),
    )
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String = HierarchyQuery.run(view, args.string("name"), args.bool("supers") ?: false, args.bool("deep") ?: false)
}
