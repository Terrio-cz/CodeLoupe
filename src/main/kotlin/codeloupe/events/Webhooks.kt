package codeloupe.events

import codeloupe.platform.IsoTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Outbound webhooks: persisted subscriptions, one delivery per matching event, signed, retried with backoff and
 * logged. A delivery waits in a coroutine only while it is pending, so nothing runs while there is nothing to send.
 */
class Webhooks(
    private val store: EventStore,
    private val key: WebhookKey,
    private val urls: WebhookUrls,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val backoffMs: List<Long> = BACKOFF_MS,
) {
    private val sending = Semaphore(PARALLEL)

    // A client per attempt: an idle java.net.http client keeps a selector thread waking every few seconds.
    private fun client(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun add(url: String, events: List<String>): Webhook {
        urls.problem(url)?.let { throw IllegalArgumentException("webhook ${Scrubber.text(url)} refused: $it") }
        events.firstOrNull { !EventTypes.validFilter(it) }?.let {
            throw IllegalArgumentException("unknown event $it; known: ${EventTypes.ALL.joinToString()} (or a prefix like job.*)")
        }
        val webhook = Webhook("w" + UUID.randomUUID().toString().take(8), url, events, IsoTime.now())
        store.putWebhook(webhook)
        return webhook.copy(url = Scrubber.text(webhook.url))
    }

    /** Why [url] may not be a webhook target, or null. */
    fun refusal(url: String): String? = urls.problem(url)

    fun remove(id: String): Boolean = store.removeWebhook(id)

    /** Subscriptions as shown to clients: a secret in a URL (`?token=…`) stays in the store. */
    fun list(): List<Webhook> = store.webhooks().map { it.copy(url = Scrubber.text(it.url)) }

    fun deliveries(limit: Int): List<Delivery> = store.deliveries(limit).map { it.copy(url = Scrubber.text(it.url)) }

    fun publish(event: Event) {
        for (webhook in store.webhooks()) if (EventTypes.matches(webhook.events, event.type)) enqueue(webhook.id, webhook.url, event)
    }

    /** A job's `webhook:` action: [event] to [url] once, with the same signing and retries. */
    fun send(url: String, event: Event) {
        urls.problem(url)?.let { throw IllegalArgumentException("webhook ${Scrubber.text(url)} refused: $it") }
        enqueue(null, url, event)
    }

    /** Picks up deliveries a previous daemon left pending. */
    fun resume() {
        for ((delivery, body) in store.pending()) launch(delivery, body)
    }

    private fun enqueue(webhookId: String?, url: String, event: Event) {
        val id = "d" + UUID.randomUUID().toString().take(12)
        val body = buildJsonObject {
            put("id", id)
            put("seq", event.seq)
            put("at", event.at)
            put("type", event.type)
            put("data", event.data)
        }.toString()
        val delivery = Delivery(id, webhookId, url, event.seq, event.type, createdAt = IsoTime.now())
        store.putDelivery(delivery, body)
        launch(delivery, body)
    }

    private fun launch(first: Delivery, body: String) {
        scope.launch(Dispatchers.IO) {
            var delivery = first
            while (delivery.state == Delivery.PENDING) {
                if (delivery.attempts > 0) delay(backoffMs[minOf(delivery.attempts, backoffMs.size) - 1])
                delivery = sending.withPermit { attempt(delivery, body) }
                store.putDelivery(delivery)
            }
            if (delivery.state == Delivery.FAILED) log("webhook ${delivery.id} to ${Scrubber.text(delivery.url)} failed after ${delivery.attempts} attempts: ${delivery.lastError}")
        }
    }

    private suspend fun attempt(delivery: Delivery, body: String): Delivery {
        val attempts = delivery.attempts + 1
        val now = IsoTime.now()
        // Checked again at every attempt: the subscription or the allowlist may have changed since.
        val refusal = urls.problem(delivery.url)
            ?: delivery.webhookId?.takeIf { id -> store.webhooks().none { it.id == id } }?.let { "subscription removed" }
        if (refusal != null) return delivery.copy(state = Delivery.FAILED, attempts = attempts, lastError = refusal, updatedAt = now)
        val timestamp = System.currentTimeMillis() / 1000
        val request = HttpRequest.newBuilder(URI(delivery.url))
            .timeout(Duration.ofSeconds(10))
            .header("content-type", "application/json")
            .header("user-agent", "codeloupe")
            .header("x-codeloupe-event", delivery.type)
            .header("x-codeloupe-delivery", delivery.id)
            .header("x-codeloupe-timestamp", timestamp.toString())
            .header("x-codeloupe-signature", key.sign(timestamp, body))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val (status, error) = try {
            client().use { it.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode() } to null
        } catch (e: Exception) {
            null to Scrubber.text("${e::class.simpleName}: ${e.message.orEmpty().take(200)}")
        }
        val state = when {
            status != null && status in 200..299 -> Delivery.DELIVERED
            status != null && status in 400..499 && status != 408 && status != 429 -> Delivery.FAILED
            attempts > backoffMs.size -> Delivery.FAILED
            else -> Delivery.PENDING
        }
        return delivery.copy(state = state, attempts = attempts, lastStatus = status, lastError = error ?: status?.takeIf { it !in 200..299 }?.let { "HTTP $it" }, updatedAt = now)
    }

    companion object {
        /** Waits before the 2nd, 3rd, … attempt: six attempts over about 36 minutes. */
        val BACKOFF_MS = listOf(2_000L, 10_000L, 60_000L, 5 * 60_000L, 30 * 60_000L)
        private const val PARALLEL = 4
    }
}
