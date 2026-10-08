package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.reconcile.ReconcilePlan
import codeloupe.reconcile.ReconcileRender
import codeloupe.reconcile.ReconcileRun
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WsReconcileCommand : CliktCommand(name = "reconcile") {
    private val now by option("--run", help = "Clean now: the auto entries, plus what --confirm / --workspace name").flag()
    private val confirm by option("--confirm", help = "Key of a confirm entry to remove (repeat)").multiple()
    private val workspaces by option("--workspace", help = "Remove every confirm entry of this workspace (repeat)").multiple()
    private val json by option("--json", help = "The daemon's JSON answer").flag()

    override fun help(context: Context) =
        "What the reconciler would clean (dry run), or with --run does it. Released workspaces' labelled resources go by themselves when config workspaces.reconcile.auto is on; everything else waits for --confirm."

    override fun run() {
        val client = DaemonClient(ConfigLoader.load())
        val doRun = now || confirm.isNotEmpty() || workspaces.isNotEmpty()
        val (status, body) = if (doRun) {
            client.send(
                "POST", "/reconcile/run",
                buildJsonObject {
                    put("confirm", JsonArray(confirm.map(::JsonPrimitive)))
                    put("workspaces", JsonArray(workspaces.map(::JsonPrimitive)))
                },
                timeout = null,
            )
        } else {
            client.send("GET", "/reconcile", timeout = null)
        }
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        if (json) return echo(body.toString())
        echo(
            if (doRun) ReconcileRender.run(JsonFormat.json.decodeFromJsonElement(ReconcileRun.serializer(), body))
            else ReconcileRender.plan(JsonFormat.json.decodeFromJsonElement(ReconcilePlan.serializer(), body)),
            trailingNewline = false,
        )
    }
}
