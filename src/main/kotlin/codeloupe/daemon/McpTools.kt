package codeloupe.daemon

import codeloupe.CodeLoupe
import codeloupe.tools.ArgsValidator
import codeloupe.tools.ToolArgs
import codeloupe.tools.Tools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema

/** An MCP server exposing the tool catalog; stateless HTTP builds one per request. */
internal class McpTools(private val runner: ToolRunner) {
    fun server(): Server {
        val server = Server(
            Implementation(name = CodeLoupe.NAME, version = CodeLoupe.VERSION),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
        )
        for (tool in Tools.ALL) {
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = ToolSchema(properties = Tools.properties(tool), required = tool.required),
            ) { request ->
                when (val checked = ArgsValidator.validate(tool, request.arguments)) {
                    is ArgsValidator.Result.Invalid -> result(checked.message, isError = true)
                    is ArgsValidator.Result.Valid -> runner.run(tool, ToolArgs(checked.args), "mcp").let { result(it.text, isError = !it.ok) }
                }
            }
        }
        return server
    }

    private fun result(text: String, isError: Boolean) = CallToolResult(content = listOf(TextContent(text)), isError = isError)
}
