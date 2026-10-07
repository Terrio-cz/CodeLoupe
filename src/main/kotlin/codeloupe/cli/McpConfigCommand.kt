package codeloupe.cli

import codeloupe.CodeLoupe
import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class McpConfigCommand : CliktCommand(name = "mcp-config") {
    override fun help(context: Context) = "Print the .mcp.json entry for Claude Code."

    override fun run() {
        val port = ConfigLoader.load().port
        echo(
            """
            {
              "${CodeLoupe.NAME}": {
                "type": "http",
                "url": "http://127.0.0.1:$port/mcp",
                "headers": {
                  "${CodeLoupe.HEADER}": "1"
                }
              }
            }
            """.trimIndent(),
        )
    }
}
