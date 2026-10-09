package codeloupe.daemon

import codeloupe.JsonFormat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The token of the test daemon on [port], found the way a local client would: `/status` names the home, the home holds the file. */
object TestToken {
    fun of(port: Int): String {
        val reply = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/status")).build(), HttpResponse.BodyHandlers.ofString())
        val home = JsonFormat.json.parseToJsonElement(reply.body()).jsonObject["home"]!!.jsonPrimitive.content
        return DaemonToken.read(Path.of(home))!!
    }
}
