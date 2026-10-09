package codeloupe.cli

import codeloupe.CodeLoupe
import codeloupe.config.Config
import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The `headersHelper` of an MCP entry: prints the headers as JSON for the client to send, the daemon token among them. */
class McpHeadersCommand(private val load: () -> Config = { ConfigLoader.load() }) : CliktCommand(name = "mcp-headers") {
    override fun help(context: Context) = "Print the headers an MCP client sends to the daemon, as JSON (for the headersHelper of the entry `mcp-config` prints)."

    override fun run() {
        echo(headers(load()).toString())
    }

    companion object {
        /** Only a daemon that proves it holds the token gets it; one from before the token existed gets the plain header it still accepts. */
        fun headers(config: Config): JsonObject {
            val token = runCatching { DaemonClient(config).trustedToken() }.getOrNull()
            val headers = linkedMapOf(CodeLoupe.HEADER to JsonPrimitive("1"))
            if (token != null) headers[CodeLoupe.TOKEN_HEADER] = JsonPrimitive(token)
            return JsonObject(headers)
        }
    }
}
