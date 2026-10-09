package codeloupe.cli

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/**
 * One HTTP request to the daemon on loopback. Plain `HttpURLConnection`: `java.net.http.HttpClient` costs ~250 classes
 * and ~100 ms of start-up, which is most of what a CLI call that takes one round trip can afford.
 */
class LocalHttp(private val base: String) {
    class Reply(val status: Int, val body: String, private val headers: Map<String, List<String>> = emptyMap()) {
        /** The first value of a response header, any case. */
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    /** [readTimeoutMs] 0 waits as long as the daemon takes. A non-2xx answer is a [Reply], not an exception. */
    fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 800,
        readTimeoutMs: Int = 0,
    ): Reply {
        // NO_PROXY: loopback never goes through a proxy, and looking the system's proxies up loads more classes.
        val connection = URI("$base$path").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null || method == "POST" || method == "PUT") {
                val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return Reply(status, stream?.use { String(it.readAllBytes(), Charsets.UTF_8) }.orEmpty(), connection.headerFields.filterKeys { it != null })
        } finally {
            connection.disconnect()
        }
    }
}
