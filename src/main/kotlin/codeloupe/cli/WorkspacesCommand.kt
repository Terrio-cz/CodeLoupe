package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceRender
import codeloupe.workspace.WorkspaceState
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.file.Path

class WorkspacesCommand : CliktCommand(name = "workspaces") {
    private val repo by option(help = "Only the repository containing this path (default: every configured one)")
    private val state by option(help = "Only workspaces in this state").enum<WorkspaceState> { it.name.lowercase() }
    private val size by option(help = "Sum the files of every directory (seconds on built worktrees)").flag()
    private val json by option("--json", help = "The daemon's JSON answer").flag()

    override fun help(context: Context) = "Every worktree of the configured repositories with branch, task, merge and tracker state; directories git does not know are orphans."

    override fun run() {
        val query = listOfNotNull(repo?.let { "repo=" + URLEncoder.encode(Path.of(it).toAbsolutePath().toString(), Charsets.UTF_8) }, if (size) "size=1" else null).joinToString("&")
        val (status, body) = DaemonClient(ConfigLoader.load()).send("GET", "/workspaces" + if (query.isEmpty()) "" else "?$query", timeout = null)
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        if (json) return echo(body.toString())
        echo(WorkspaceRender.text(JsonFormat.json.decodeFromJsonElement(WorkspaceList.serializer(), body), state?.let { setOf(it) } ?: WorkspaceState.entries.toSet()), trailingNewline = false)
    }
}
