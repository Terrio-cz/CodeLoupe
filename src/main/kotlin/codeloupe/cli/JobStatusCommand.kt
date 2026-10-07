package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.jobs.JobRecord
import codeloupe.jobs.JobReport
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class JobStatusCommand : CliktCommand(name = "status") {
    private val id by argument(help = "Job id; without it the latest jobs").optional()
    private val limit by option(help = "How many of the latest jobs (default 10)").int().default(10)

    override fun help(context: Context) = "One job (with its follow-ups) or the latest jobs, without waiting."

    override fun run() {
        val client = DaemonClient(ConfigLoader.load())
        val (status, body) = if (id == null) client.send("GET", "/jobs?limit=$limit") else client.send("GET", "/jobs/$id")
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        if (id != null) return echo(body.getValue("text").jsonPrimitive.content)
        val jobs = body.getValue("items").jsonArray.map { JsonFormat.json.decodeFromJsonElement(JobRecord.serializer(), it) }
        echo(jobs.joinToString("\n") { JobReport.line(it) }.ifEmpty { "no jobs" })
    }
}
