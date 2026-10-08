package codeloupe.tools

import codeloupe.query.View
import codeloupe.query.usages.HierarchyQuery

object HierarchyTool : ViewTool {
    override val name = "hierarchy"
    override val description = "Supertypes and subtypes (implementations, object expressions included) of a type, and lambdas " +
        "converted to a fun interface; or what a member overrides and what overrides it. Supertypes are the direct ones; deep=true " +
        "adds theirs."
    override val properties = Schema.properties(
        "name" to Schema.string("Type, pkg.Type or Type.member"),
        "deep" to Schema.boolean("Also the supertypes of the supertypes"),
    )
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String = HierarchyQuery.run(view, args.string("name"), args.bool("deep") ?: false)
}
