package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.youtrack.JdkTransport
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerSettingsTest {
    @Test
    fun `settings come from config json and never show the token`() {
        val home = TestRepos.tmpDir("settings")
        val tokenFile = home.resolve("token.txt")
        Files.writeString(tokenFile, "# comment\nexport YT='tok-123'\n")
        Files.writeString(
            home.resolve("config.json"),
            """{ "trackers": [
                 { "name": "acme", "url": "https://acme.youtrack.cloud/", "projects": ["ter", "CL"], "token": { "dotenv": ${JsonPrimitive(tokenFile.toString())}, "key": "YT" }, "repos": ["C:/r"] },
                 { "url": "https://x.example", "projects": ["X"], "token": { "env": "X_TOKEN" } },
                 { "url": "https://no-token.example", "projects": ["Y"] },
                 { "url": "https://user:pw@creds.example", "projects": ["Z"], "token": { "env": "X_TOKEN" } }
               ], "trackerSyncMinutes": 2, "trackerIdleMinutes": 5 }""",
        )
        val settings = TrackerSettingsLoader.load(home) { mapOf("X_TOKEN" to "env-secret") }
        assertEquals(listOf("acme", "x"), settings.instances.map { it.name })
        assertEquals(2, settings.problems.size, settings.problems.toString())
        assertTrue(settings.problems.any { "url must not carry credentials" in it } && settings.problems.none { "pw" in it })
        val acme = settings.instances[0]
        assertEquals(listOf("TER", "CL"), acme.projects)
        assertEquals("https://acme.youtrack.cloud", acme.url)
        assertEquals("tok-123", acme.token.read())
        assertEquals("env-secret", settings.instances[1].token.read())
        assertEquals(120_000, settings.syncMs)
        assertEquals(300_000, settings.idleMs)
        assertFalse("tok-123" in settings.toString() || "env-secret" in settings.toString(), settings.toString())
        val missing = runCatching { TokenSource.Env("NOPE") { emptyMap() }.read() }.exceptionOrNull()!!
        assertEquals("token variable NOPE is not set", missing.message)
    }

    @Test
    fun `a token the JDK would quote in an error is refused before any request`() {
        val home = TestRepos.tmpDir("bad-token")
        for (bad in listOf("\u201Cabc-secret-1\u201D", "abc-secret-2\u200B", "abc secret 3")) {
            val file = home.resolve("t.txt")
            Files.writeString(file, "K=$bad\n")
            val error = runCatching { JdkTransport("http://127.0.0.1:9", TokenSource.DotEnv(file, "K")).get("/api/issues/X-1") }.exceptionOrNull()!!
            assertEquals("the token has characters not allowed in an HTTP header", error.message)
        }
    }
}
