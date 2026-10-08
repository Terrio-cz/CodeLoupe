package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.reconcile.ReleaseStatus
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path

class WsReleaseCommand : CliktCommand(name = "release") {
    private val target by argument(name = "TARGET", help = "A worktree directory, a worktree name or a task id").optional()
    private val repo by option("--repo", help = "A path in the repository, when the name alone is not enough")
    private val list by option("--list", help = "Show the released workspaces and what is left of them").flag()
    private val json by option("--json", help = "The daemon's JSON answer").flag()

    override fun help(context: Context) =
        "Mark a workspace released: its Docker resources are cleaned in the background, with retries. Returns at once, whatever Docker is doing."

    override fun run() {
        val client = DaemonClient(ConfigLoader.load())
        if (target == null) {
            if (!list) throw ProgramResult(2).also { echo("give the workspace to release, or --list", err = true) }
            val (status, body) = client.send("GET", "/workspaces/releases", timeout = null)
            return show(status, body)
        }
        val (status, body) = client.send(
            "POST", "/workspaces/release",
            buildJsonObject {
                put("target", if (looksLikePath(target!!)) Path.of(target!!).toAbsolutePath().normalize().toString() else target!!)
                repo?.let { put("repo", Path.of(it).toAbsolutePath().normalize().toString()) }
            },
            timeout = null,
        )
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        if (json) return echo(body.toString())
        echo("released ${body.getValue("workspace").jsonPrimitive.content} of ${body.getValue("repo").jsonPrimitive.content}; cleanup runs in the background (ws release --list)")
    }

    private fun show(status: Int, body: JsonObject) {
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        val items = JsonFormat.json.decodeFromString(ListSerializer(ReleaseStatus.serializer()), body["items"]?.toString() ?: "[]")
        if (json) return echo(JsonFormat.json.encodeToString(ListSerializer(ReleaseStatus.serializer()), items))
        if (items.isEmpty()) return echo("no workspace is waiting for its cleanup")
        for (r in items) echo("${r.workspace} (${r.repo})  released ${r.at.take(19)}  " + (r.pending?.let { "$it left" + if ((r.retrying ?: 0) > 0) ", ${r.retrying} retrying" else "" } ?: "not looked at yet"))
    }

    private fun looksLikePath(text: String) = text.contains('/') || text.contains('\\')
}
