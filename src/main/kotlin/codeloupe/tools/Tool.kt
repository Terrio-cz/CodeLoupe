package codeloupe.tools

import codeloupe.query.View
import kotlinx.serialization.json.JsonObject

/** One tool, shared by MCP and the HTTP API/CLI: one definition, one implementation. */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema `properties` of the arguments; `root` is added by the catalog. */
    val properties: JsonObject
    val required: List<String>

    fun run(view: View, args: ToolArgs): String
}
