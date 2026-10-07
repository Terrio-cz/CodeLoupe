package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path

class JobStartCommand : CliktCommand(name = "start") {
    init {
        // Everything from the command on belongs to it: `job start ./gradlew test --info`.
        context { allowInterspersedArgs = false }
    }

    private val slot by option(help = "Named resource to hold while running (e.g. gradle-test); waits in the daemon when busy")
    private val cwd by option(help = "Directory to run in (default: the current one)").default(Path.of("").toAbsolutePath().toString())
    private val env by option("--env", help = "K=V added to the job's environment (never stored)").associate()
    private val then by option(
        "--then",
        help = "Step after success: job[@slot]:<command>, notify[:message] or webhook:<url>, optionally prefixed by a condition (failed==0 ?)",
    ).multiple()
    private val onFailure by option("--on-failure", help = "Step after a failure, same forms as --then").multiple()
    private val wake by option(help = "always | failure | never: whether the chain's end wakes the agent (default: failure with --then, else always)")
    private val tag by option(help = "Label carried into events (default: CODELOUPE_JOB_TAG)")
    private val wait by option(help = "Then block like `job wait` (run it as a background task)").flag()
    private val command by argument(help = "Program and arguments; no shell (use bash -c for pipes)").multiple(required = true)

    override fun help(context: Context) = "Start a job; prints its id at once. The policy hook judges the command first."

    override fun run() {
        val client = DaemonClient(ConfigLoader.load())
        val body = buildJsonObject {
            put("command", JsonArray(command.map(::JsonPrimitive)))
            put("cwd", Path.of(cwd).toAbsolutePath().normalize().toString())
            put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) }))
            slot?.let { put("slot", it) }
            put("then", JsonArray(then.map(::JsonPrimitive)))
            put("onFailure", JsonArray(onFailure.map(::JsonPrimitive)))
            wake?.let { put("wake", it) }
            (tag ?: System.getenv("CODELOUPE_JOB_TAG")?.takeIf { it.isNotBlank() })?.let { put("tag", it) }
        }
        val (status, answer) = client.send("POST", "/jobs", body)
        when (status) {
            200 -> Unit
            403 -> {
                val verdict = answer["refused"]?.jsonPrimitive?.content
                echo("not started - the policy hook answered $verdict: ${answer["reason"]?.jsonPrimitive?.content}", err = true)
                throw ProgramResult(2)
            }
            else -> {
                echo("error: ${answer["error"]?.jsonPrimitive?.content ?: "HTTP $status"}", err = true)
                throw ProgramResult(1)
            }
        }
        val id = answer.getValue("job").jsonObject.getValue("id").jsonPrimitive.content
        echo(answer.getValue("text").jsonPrimitive.content)
        if (!wait) {
            echo("wait: codeloupe job wait $id   (as a background task: one notification when it ends)")
            return
        }
        val (exit, text) = JobWaiter(client).wait(id)
        echo(text)
        if (exit != 0) throw ProgramResult(exit)
    }
}
