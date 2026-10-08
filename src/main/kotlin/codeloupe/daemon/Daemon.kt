package codeloupe.daemon

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.config.Config
import codeloupe.config.PortPolicy
import codeloupe.events.EventBus
import codeloupe.events.EventStore
import codeloupe.events.WebhookKey
import codeloupe.events.WebhookUrls
import codeloupe.events.Webhooks
import codeloupe.events.eventRoutes
import codeloupe.jobs.JobRunner
import codeloupe.jobs.JobTool
import codeloupe.jobs.jobRoutes
import codeloupe.platform.IsoTime
import codeloupe.platform.ProcessMemory
import codeloupe.platform.Timings
import codeloupe.repo.Registry
import codeloupe.tracker.TrackerSettingsLoader
import codeloupe.tracker.Trackers
import codeloupe.tools.ToolArgs
import codeloupe.tools.Tools
import codeloupe.workspace.Workspaces
import codeloupe.workspace.workspaceRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.BindException
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.system.exitProcess

/**
 * The single CodeLoupe daemon: one process for every client on the machine. Serves MCP (stateless Streamable
 * HTTP) on /mcp, the same tools as JSON on /api/<tool> for the CLI, and /status.
 */
class Daemon private constructor(
    val config: Config,
    private val exitOnShutdown: Boolean,
    webhookBackoffMs: List<Long>,
) {
    private val started = Instant.now()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = AppendLog(config.home.resolve("daemon.log"))
    private val queue = JobQueue(scope)
    private val eventStore = EventStore(config.home.resolve("events.db"))
    private val webhookKey = WebhookKey(config.home.resolve("webhook.key"))
    private val webhooks = Webhooks(eventStore, webhookKey, WebhookUrls(config.port, config.jobs.remoteWebhooks), scope, ::log, webhookBackoffMs)
    val events = EventBus(eventStore, webhooks)
    val registry = Registry(config, queue, log = ::log, emit = events::emit)
    val jobs = JobRunner(config.home, config.jobs, events, webhooks, scope, ::log)
    private val trackers = Trackers.open(TrackerSettingsLoader.load(config.home), config.home, scope, ::log)
    private val tools = Tools.catalog(trackers)
    private val workspaces = Workspaces(config, registry, trackers)
    private val runner = ToolRunner(registry, config.defaultRoot, AppendLog(config.home.resolve("calls.jsonl")), onCall = trackers::touch)
    private val guard = RequestGuard(config.port)
    private val infoFile = config.home.resolve("daemon.json")
    private val pid = ProcessHandle.current().pid()
    private lateinit var server: EmbeddedServer<*, *>

    fun status(): DaemonStatus {
        val runtime = Runtime.getRuntime()
        val cpu = ProcessHandle.current().info().totalCpuDuration().map { it.toSeconds() }.orElse(0)
        return DaemonStatus(
            name = CodeLoupe.NAME, version = CodeLoupe.VERSION, pid = pid, port = config.port, home = config.home.toString(),
            uptimeSec = Instant.now().epochSecond - started.epochSecond, rssMb = ProcessMemory.rssMb(),
            heapMb = (runtime.totalMemory() - runtime.freeMemory()) / MB, cpuSec = cpu,
            calls = runner.stats(), queue = queue.snapshot(), repos = registry.snapshot(), jobs = jobs.snapshot(), trackers = trackers.summary(),
            gitSpawns = Timings.gitSpawns(), timings = Timings.snapshot(),
        )
    }

    fun stop() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
        jobs.shutdown()
        scope.cancel()
        jobs.close()
        eventStore.close()
        trackers.close()
        registry.close()
        runCatching {
            val info = JsonFormat.json.decodeFromString(DaemonInfo.serializer(), Files.readString(infoFile))
            if (info.pid == pid) Files.deleteIfExists(infoFile)
        }
    }

    private fun log(message: String) = log.append("${IsoTime.now()} $message")

    private fun start() {
        Files.createDirectories(config.home)
        // Class-data archives of earlier versions; the daemon no longer writes one.
        config.home.listDirectoryEntries("daemon-*.jsa").forEach { runCatching { Files.deleteIfExists(it) } }
        // CIO keeps a connection open until the client closes it or it idles; clients drop it on `Connection: close`.
        server = embeddedServer(
            CIO,
            configure = {
                connector {
                    host = "127.0.0.1"
                    port = config.port
                }
                connectionIdleTimeoutSeconds = IDLE_SECONDS
            },
        ) { module() }
        try {
            server.start(wait = false)
        } catch (e: Exception) {
            server.stop(0, 0)
            scope.cancel()
            jobs.close()
            eventStore.close()
            throw generateSequence<Throwable>(e) { it.cause }.filterIsInstance<BindException>().firstOrNull() ?: e
        }
        // Only once the port is ours: a second daemon that fails to bind must not touch the first one's jobs.
        jobs.recover()
        webhooks.resume()
        val info = DaemonInfo(pid, config.port, CodeLoupe.VERSION, IsoTime.of(started))
        Files.writeString(infoFile, JsonFormat.json.encodeToString(DaemonInfo.serializer(), info))
        log("daemon ${CodeLoupe.VERSION} pid $pid listening on 127.0.0.1:${config.port}")
    }

    private fun Application.module() {
        intercept(ApplicationCallPipeline.Plugins) {
            // No keep-alive: a pooled socket would outlive a daemon restart and fail the client's next call.
            val call = context
            call.response.header(HttpHeaders.Connection, "close")
            guard.refusal(call)?.let {
                call.respondJson(RequestGuard.STATUS, error(it))
                return@intercept finish()
            }
            try {
                proceed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // First line only: parser messages quote the request body, and logs never hold content.
                val reason = "${e::class.simpleName}: ${e.message.orEmpty().lineSequence().first()}"
                log("request ${call.request.httpMethod.value} ${call.request.path()} failed: $reason")
                if (!call.response.isCommitted) call.respondJson(HttpStatusCode.InternalServerError, error(reason))
            }
        }
        val mcp = McpTools(runner, tools, JobTool(jobs))
        mcpStatelessStreamableHttp(path = "/mcp") { mcp.server() }
        routing {
            get("/status") { call.respondJson(HttpStatusCode.OK, DaemonStatus.serializer(), status()) }
            post("/api/{tool}") {
                val name = call.parameters["tool"].orEmpty()
                val tool = tools.firstOrNull { it.name == name }
                    ?: return@post call.respondJson(HttpStatusCode.NotFound, error("unknown tool"))
                val args = readBody(call) ?: return@post call.respondJson(HttpStatusCode.InternalServerError, error("body too large"))
                call.respondJson(HttpStatusCode.OK, ToolOutcome.serializer(), runner.run(tool, ToolArgs(args), "api"))
            }
            jobRoutes(jobs)
            workspaceRoutes(workspaces)
            eventRoutes(events, webhooks, webhookKey)
            post("/shutdown") {
                val pending = jobs.pending()
                if (pending > 0 && call.parameters["force"] != "1") {
                    return@post call.respondJson(HttpStatusCode.Conflict, error("$pending jobs queued or running; stop --force ends them (they become lost)"))
                }
                call.respondJson(HttpStatusCode.OK, buildJsonObject { put("ok", true) })
                log("shutdown requested")
                scope.launch {
                    stop()
                    if (exitOnShutdown) exitProcess(0)
                }
            }
            route("{...}") { handle { call.respondJson(HttpStatusCode.NotFound, error("not found")) } }
        }
    }

    private suspend fun readBody(call: ApplicationCall): JsonObject? {
        val bytes = call.receiveChannel().readRemaining(MAX_BODY + 1).readByteArray()
        if (bytes.size > MAX_BODY) return null
        val text = bytes.toString(Charsets.UTF_8)
        return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonObject) =
        respondText(body.toString(), ContentType.Application.Json, status)

    private suspend fun <T> ApplicationCall.respondJson(status: HttpStatusCode, serializer: KSerializer<T>, body: T) =
        respondText(JsonFormat.json.encodeToString(serializer, body), ContentType.Application.Json, status)

    companion object {
        private const val MB = 1024L * 1024
        private const val MAX_BODY = 4L * 1024 * 1024
        private const val IDLE_SECONDS = 2

        /**
         * Binds 127.0.0.1:<port>; fails with a BindException while another daemon holds the port, and with an
         * IllegalStateException when a non-default home asks for the default home's port ([PortPolicy]).
         */
        fun start(config: Config, exitOnShutdown: Boolean = false, webhookBackoffMs: List<Long> = Webhooks.BACKOFF_MS): Daemon {
            PortPolicy.refusal(config)?.let { throw IllegalStateException(it) }
            return Daemon(config, exitOnShutdown, webhookBackoffMs).apply { start() }
        }
    }
}
