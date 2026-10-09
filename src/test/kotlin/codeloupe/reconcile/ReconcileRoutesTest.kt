package codeloupe.reconcile

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import codeloupe.daemon.TestToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcileRoutesTest {
    private fun auto(body: String) = wantsAuto(Json.parseToJsonElement(body).jsonObject)

    @Test
    fun `a call runs the auto entries unless it says it does not`() {
        assertEquals(true, auto("{}"))
        assertEquals(true, auto("""{"confirm":["volume:x"]}"""))
        assertEquals(false, auto("""{"confirm":["volume:x"],"auto":false}"""))
        assertEquals(true, auto("""{"auto":"no"}"""), "only a real false switches it off")
    }

    private fun call(port: Int, method: String, path: String, body: String? = null): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, TestToken.of(port))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `a confirm needs the hash of the plan that was shown, a matching one runs, an older one is refused with the current plan`() {
        val port = ServerSocket(0).use { it.localPort }
        val daemon = Daemon.start(Config(TestRepos.tmpDir("home"), port, 60_000, 120_000, 512, null))
        try {
            val hash = Json.parseToJsonElement(call(port, "GET", "/reconcile").body()).jsonObject["planHash"]!!.jsonPrimitive.content
            assertTrue(hash.isNotEmpty())

            val missing = call(port, "POST", "/reconcile/run", """{"confirm":["volume:x"]}""")
            assertEquals(428, missing.statusCode())
            assertContains(missing.body(), "planHash")
            assertEquals(428, call(port, "POST", "/reconcile/run", """{"workspaces":["TER-1"]}""").statusCode())

            val stale = call(port, "POST", "/reconcile/run", """{"confirm":["volume:x"],"planHash":"0000"}""")
            assertEquals(409, stale.statusCode())
            val answer = Json.parseToJsonElement(stale.body()).jsonObject
            assertEquals(hash, answer["plan"]!!.jsonObject["planHash"]!!.jsonPrimitive.content)

            assertEquals(200, call(port, "POST", "/reconcile/run", """{"confirm":["volume:x"],"planHash":"$hash"}""").statusCode())
            assertEquals(200, call(port, "POST", "/reconcile/run", "{}").statusCode(), "a run that confirms nothing needs no hash")
        } finally {
            daemon.stop()
        }
    }
}
