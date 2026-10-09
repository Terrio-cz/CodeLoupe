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

    /** Whether the tool answers for a repository; a tool that does not still gets `root` (may be empty) as the caller's key. */
    val needsRoot: Boolean get() = true

    /**
     * Whether the tool acts for the user (runs a process, writes a file or a tracker issue, names secrets) rather than reads code: such a
     * tool is refused to a caller that has not presented the daemon's token.
     */
    val mutating: Boolean get() = false

    /** The answer for the repository or worktree at [root]. */
    suspend fun answer(registry: Registry, root: String, args: ToolArgs): String
}
