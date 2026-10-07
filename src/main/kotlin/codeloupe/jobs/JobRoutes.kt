package codeloupe.jobs

import codeloupe.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `POST /jobs` (403 when the policy refuses), `GET /jobs`, `GET /jobs/{id}`, `POST /jobs/{id}/cancel`, and the long poll
 * `GET /jobs/{id}/wait?timeoutSec=` that answers when the job's chain ends (or the timeout passes, `done: false`).
 */
fun Route.jobRoutes(jobs: JobRunner) {
    post("/jobs") {
        val request = runCatching { JsonFormat.json.decodeFromString(JobRequest.serializer(), call.receiveText()) }.getOrElse {
            return@post call.respondJson(error("send {\"command\": [...], \"cwd\": \"…\"}: ${it.message?.lineSequence()?.first()}"), HttpStatusCode.BadRequest)
        }
        val submission = try {
            jobs.submit(request)
        } catch (e: IllegalArgumentException) {
            return@post call.respondJson(error(e.message.orEmpty()), HttpStatusCode.BadRequest)
        }
        when (submission) {
            is Submission.Accepted -> call.respondJson(
                buildJsonObject {
                    put("job", record(submission.job))
                    put("text", JobReport.line(submission.job, jobs.ahead(submission.job)))
                },
            )
            is Submission.Refused -> call.respondJson(
                buildJsonObject {
                    put("refused", submission.decision.verdict.name.lowercase())
                    put("reason", submission.decision.reason)
                },
                HttpStatusCode.Forbidden,
            )
        }
    }
    get("/jobs") {
        val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 500)
        call.respondJson(buildJsonObject { put("items", JsonFormat.json.encodeToJsonElement(ListSerializer(JobRecord.serializer()), jobs.list(limit))) })
    }
    get("/jobs/{id}") {
        val chain = jobs.chain(call.parameters["id"].orEmpty())
        if (chain.isEmpty()) return@get call.respondJson(error("no such job"), HttpStatusCode.NotFound)
        call.respondJson(chainBody(chain, jobs))
    }
    get("/jobs/{id}/wait") {
        val id = call.parameters["id"].orEmpty()
        if (jobs.get(id) == null) return@get call.respondJson(error("no such job"), HttpStatusCode.NotFound)
        val timeoutSec = (call.parameters["timeoutSec"]?.toLongOrNull() ?: 300).coerceIn(1, 600)
        call.respondJson(chainBody(jobs.await(id, timeoutSec * 1000), jobs))
    }
    post("/jobs/{id}/cancel") {
        val job = jobs.cancel(call.parameters["id"].orEmpty()) ?: return@post call.respondJson(error("no such job"), HttpStatusCode.NotFound)
        call.respondJson(buildJsonObject { put("job", record(job)) })
    }
}

/** `done` once the chain's last job ended; `exit` is what `job wait` exits with; `text` the compact report. */
private fun chainBody(chain: List<JobRecord>, jobs: JobRunner) = buildJsonObject {
    val done = chain.last().status.terminal
    put("done", done)
    put("exit", JobReport.exitCode(chain))
    put("text", if (done) JobReport.text(chain) else JobReport.line(chain.last(), jobs.ahead(chain.last())))
    put("chain", JsonFormat.json.encodeToJsonElement(ListSerializer(JobRecord.serializer()), chain))
}

private fun record(job: JobRecord) = JsonFormat.json.encodeToJsonElement(JobRecord.serializer(), job)

private fun error(message: String) = buildJsonObject { put("error", message) }

private suspend fun ApplicationCall.respondJson(body: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body.toString(), ContentType.Application.Json, status)
