package codeloupe.tools

import codeloupe.repo.Registry
import kotlinx.serialization.json.JsonObject

/** One tool, shared by MCP and the HTTP API/CLI: one definition, one implementation. */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema `properties` of the arguments; `root` is added by the catalog. */
    val properties: JsonObject
    val required: List<String>

    /** The answer for the repository or worktree at [root]. */
    suspend fun answer(registry: Registry, root: String, args: ToolArgs): String
}
