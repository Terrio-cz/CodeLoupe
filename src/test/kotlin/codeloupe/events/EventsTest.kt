package codeloupe.events

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.JobsConfig
import codeloupe.daemon.Daemon
import codeloupe.jobs.FakeJob
import codeloupe.jobs.JobRequest
import codeloupe.jobs.Submission
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventsTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("events-home")
    private val work = TestRepos.tmpDir("events-work")
    private val config = Config(home, port, 60_000, 120_000, 512, null, jobs = JobsConfig(remoteWebhooks = setOf("https://hooks.example.com")))
    private val daemon = Daemon.start(config, webhookBackoffMs = listOf(100, 200, 400))
    private val http = HttpClient.newHttpClient()

    /** A receiver on this machine that fails [failFirst] times, then accepts; keeps what it got. */
    private inner class Receiver(private val failFirst: Int) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<Map<String, String>, String>>()
        private val seen = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hook") { exchange ->
                val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                requests += exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() } to body
                exchange.sendResponseHeaders(if (seen.incrementAndGet() <= failFirst) 503 else 204, -1)
                exchange.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/hook"

        override fun close() = server.stop(0)
    }

    @AfterAll
    fun stop() = daemon.stop()

    @Test
    fun `webhooks are persisted, signed, and retried with backoff until delivered`() {
        Receiver(failFirst = 2).use { receiver ->
            val webhook = subscribe(receiver.url, "job.finished")
            runJob("print=ok")
            val delivery = waitForDelivery { it.webhookId == webhook && it.state != Delivery.PENDING }
            assertEquals(Delivery.DELIVERED, delivery.state)
            assertEquals(3, delivery.attempts, "two 503s, then delivered")
            assertEquals(3, receiver.requests.size)
            val (headers, body) = receiver.requests.last()
            val key = WebhookKey(home.resolve("webhook.key"))
            assertEquals(key.sign(headers.getValue("x-codeloupe-timestamp").toLong(), body), headers.getValue("x-codeloupe-signature"))
            assertEquals("job.finished", headers.getValue("x-codeloupe-event"))
            assertEquals("job.finished", JsonFormat.json.parseToJsonElement(body).jsonObject.getValue("type").jsonPrimitive.content)
            val listed = get("/webhooks").getValue("items").toString()
            assertContains(listed, receiver.url)
            assertFalse(Files.readString(home.resolve("webhook.key")).trim() in listed, "the key never leaves the file")
            assertEquals(200, delete("/webhooks/$webhook"))
        }
    }

    @Test
    fun `a delivery that keeps failing ends failed after the last backoff`() {
        Receiver(failFirst = 100).use { receiver ->
            val webhook = subscribe(receiver.url, "job.*")
            runJob("print=x")
            val delivery = waitForDelivery { it.webhookId == webhook && it.type == "job.finished" && it.state != Delivery.PENDING }
            assertEquals(Delivery.FAILED, delivery.state)
            assertEquals(4, delivery.attempts, "the first try and one per backoff step")
            assertEquals(503, delivery.lastStatus)
            delete("/webhooks/$webhook")
        }
    }

    @Test
    fun `webhook targets are local unless allowlisted, never the daemon itself`() {
        val urls = WebhookUrls(port, setOf("https://hooks.example.com"))
        assertNull(urls.problem("http://127.0.0.1:9000/x"))
        assertNull(urls.problem("http://localhost:9000/x"))
        assertNull(urls.problem("https://hooks.example.com/ci"))
        assertNotNull(urls.problem("http://127.0.0.1:$port/shutdown"))
        assertNotNull(urls.problem("https://evil.example.com/x"))
        assertNotNull(urls.problem("http://hooks.example.com/ci"), "remote needs https")
        assertNotNull(urls.problem("http://user:pw@127.0.0.1:9000/x"))
        assertNotNull(urls.problem("file:///etc/passwd"))
        val refused = post("/webhooks", """{"url": "https://evil.example.com/x", "events": ["job.finished"]}""")
        assertEquals(400, refused.statusCode())
        assertEquals(400, post("/webhooks", """{"url": "http://127.0.0.1:9/x", "events": ["nonsense"]}""").statusCode())
    }

    @Test
    fun `events and webhook payloads carry no secrets`() {
        Receiver(failFirst = 0).use { receiver ->
            val webhook = subscribe(receiver.url, "job.finished")
            val since = daemon.events.lastSeq()
            runJob(
                "--token=tok_9f8e7d6c5b4a", "print=Authorization: Bearer abcdefghijklmnopqrstuvwx", "print=password=hunter2hunter2",
                "print=https://bob:s3cretpw@example.com/repo.git", "print=ghp_0123456789abcdefghijklmnopqrstuvwxyz",
                env = mapOf("API_TOKEN" to "env-secret-value-123"),
            )
            waitForDelivery { it.webhookId == webhook && it.state == Delivery.DELIVERED }
            val texts = daemon.events.since(since, 100).map { it.data.toString() } + receiver.requests.map { it.second }
            for (secret in listOf("tok_9f8e7d6c5b4a", "abcdefghijklmnopqrstuvwx", "hunter2hunter2", "s3cretpw", "ghp_0123456789", "env-secret-value-123")) {
                assertTrue(texts.none { secret in it }, "$secret leaked")
            }
            assertTrue(texts.any { "API_TOKEN" in it }, "variables are reported by name")
            delete("/webhooks/$webhook")
        }
    }

    @Test
    fun `the event stream replays after Last-Event-ID and then follows live`() {
        val since = daemon.events.lastSeq()
        val first = runJob("print=one")
        val lines = CopyOnWriteArrayList<String>()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/events/stream"))
            .header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).header("Last-Event-ID", since.toString()).GET().build()
        val reader = thread {
            runCatching {
                http.send(request, HttpResponse.BodyHandlers.ofLines()).body().use { stream ->
                    stream.takeWhile { lines.count { it.startsWith("data: ") && "\"type\":\"job.finished\"" in it } < 2 }.forEach { lines += it }
                }
            }
        }
        // Past CIO's 2 s idle timeout: a quiet stream must stay open.
        Thread.sleep(3000)
        val second = runJob("print=two")
        reader.join(15_000)
        val data = lines.filter { it.startsWith("data: ") }.map { JsonFormat.json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        val finished = data.filter { it.getValue("type").jsonPrimitive.content == "job.finished" }.map { it.getValue("data").jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf(first, second), finished, "the replayed job, then the live one")
        val ids = lines.filter { it.startsWith("id: ") }.map { it.removePrefix("id: ").toLong() }
        assertEquals(ids.sorted(), ids)
        assertTrue(ids.first() > since)
    }

    private fun runJob(vararg args: String, env: Map<String, String> = emptyMap()): String = runBlocking {
        val submission = daemon.jobs.submit(JobRequest(FakeJob.command(*args), work.toString(), env))
        val id = (submission as Submission.Accepted).job.id
        daemon.jobs.await(id, 60_000)
        id
    }

    private fun subscribe(url: String, vararg events: String): String {
        val response = post("/webhooks", """{"url": "$url", "events": [${events.joinToString { "\"$it\"" }}]}""")
        assertEquals(200, response.statusCode(), response.body())
        return JsonFormat.json.parseToJsonElement(response.body()).jsonObject.getValue("webhook").jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun waitForDelivery(condition: (Delivery) -> Boolean): Delivery {
        repeat(200) {
            deliveries().firstOrNull(condition)?.let { return it }
            Thread.sleep(50)
        }
        return assertNotNull(deliveries().firstOrNull(condition), "no matching delivery: ${deliveries()}")
    }

    private fun deliveries(): List<Delivery> =
        JsonFormat.json.parseToJsonElement(get("/webhooks/deliveries?limit=100").getValue("items").toString())
            .let { JsonFormat.json.decodeFromJsonElement(ListSerializer(Delivery.serializer()), it) }

    private fun get(path: String) = JsonFormat.json.parseToJsonElement(
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).GET().build(), HttpResponse.BodyHandlers.ofString()).body(),
    ).jsonObject

    private fun post(path: String, body: String) = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun delete(path: String) =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
}
