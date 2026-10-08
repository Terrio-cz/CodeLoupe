package codeloupe.uiapi

import codeloupe.JsonFormat
import codeloupe.repo.BusyException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.request.httpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val BASE = "/ui-api/v1"

/** `GET /ui-api/v1/<resource>`; anything but GET is 405. The request guard (Host, Origin, `x-codeloupe`) has already run. */
fun Route.uiApiRoutes(api: UiApi) {
    route(BASE) {
        get("nav") { call.answer(Nav.serializer()) { api.nav(call.query("gapsSince")) } }
        get("overview") { call.answer(Overview.serializer()) { api.overview(call.query("range"), call.query("account")) } }
        get("accounts") { call.answer(AccountsView.serializer()) { api.accounts() } }
        get("worktrees") { call.answer(WorktreeList.serializer()) { api.worktrees(call.query("repo"), call.query("layer"), call.query("q")) } }
        get("worktrees/{id}") { call.answer(WorktreeDetail.serializer()) { api.worktree(call.parameters["id"].orEmpty()) } }
        get("tasks") { call.answer(TaskPage.serializer()) { api.tasks(call.query("project"), call.query("state"), call.query("q"), call.query("limit"), call.query("cursor")) } }
        get("tasks/{id}") { call.answer(TaskDetail.serializer()) { api.task(call.parameters["id"].orEmpty()) } }
        get("index") { call.answer(IndexHealth.serializer()) { api.index() } }
        get("runs") { call.answer(RunPage.serializer()) { api.runs(call.query("range"), call.query("sort"), call.query("role"), call.query("q"), call.query("ter"), call.query("limit"), call.query("cursor")) } }
        get("runs/{id}") { call.answer(RunDetail.serializer()) { api.run(call.parameters["id"].orEmpty()) } }
        get("runs/{id}/steps") { call.answer(StepPage.serializer()) { api.steps(call.parameters["id"].orEmpty(), call.query("sort"), call.query("limit"), call.query("cursor")) } }
        get("gaps") { call.answer(Gaps.serializer()) { api.gaps(call.query("range"), call.query("tool"), call.query("reason")) } }
        get("environment") { call.answer(EnvironmentView.serializer()) { api.environment() } }
        get("environment/audit") { call.answer(EnvironmentAuditView.serializer()) { api.environmentAudit(call.query("name"), call.query("scope"), call.query("limit")) } }
        get("settings") { call.answer(SettingsView.serializer()) { api.settings() } }
        get("events") { call.answer(EventsView.serializer()) { api.events(call.query("since"), call.query("limit")) } }
        route("{...}") {
            handle {
                if (call.request.httpMethod == HttpMethod.Get) call.fail(UiApiException.notFound("no such resource"))
                else {
                    call.response.header(HttpHeaders.Allow, "GET")
                    call.fail(UiApiException("bad_request", "the UI API is read-only: GET only", HttpStatusCode.MethodNotAllowed))
                }
            }
        }
    }
}

private fun ApplicationCall.query(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotEmpty() }

private suspend fun <T> ApplicationCall.answer(serializer: KSerializer<T>, body: suspend () -> T) {
    try {
        val text = JsonFormat.json.encodeToString(serializer, body())
        response.header(HttpHeaders.CacheControl, "no-store")
        respondText(text, ContentType.Application.Json.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    } catch (e: CancellationException) {
        throw e
    } catch (e: UiApiException) {
        fail(e)
    } catch (e: BusyException) {
        fail(UiApiException.busy(e.message.orEmpty()))
    } catch (e: IllegalArgumentException) {
        fail(UiApiException.badRequest(e.message.orEmpty().lineSequence().first()))
    }
}

private suspend fun ApplicationCall.fail(e: UiApiException) {
    response.header(HttpHeaders.CacheControl, "no-store")
    val body = buildJsonObject {
        put("error", buildJsonObject {
            put("code", e.code)
            put("message", e.message.orEmpty())
        })
    }
    respondText(body.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8), e.status)
}
