package codeloupe.tools

import codeloupe.query.View
import codeloupe.query.usages.HierarchyQuery

object HierarchyTool : ViewTool {
    override val name = "hierarchy"
    override val description = "Supertypes and subtypes (implementations) of a type, or what a member overrides and what overrides it."
    override val properties = Schema.properties("name" to Schema.string("Type, pkg.Type or Type.member"))
    override val required = listOf("name")

    override fun run(view: View, args: ToolArgs): String = HierarchyQuery.run(view, args.string("name"))
}
