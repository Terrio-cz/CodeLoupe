package codeloupe.hooks

import codeloupe.TestRepos
import codeloupe.accounts.ClaudeAccount
import codeloupe.accounts.ClaudeLogin
import codeloupe.accounts.LoginFiles
import codeloupe.accounts.SessionIdentities
import codeloupe.config.HooksConfig
import codeloupe.daemon.AppendLog
import codeloupe.metrics.AccountIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `SessionStart` and `SubagentStart` hooks note which login runs a session (CL-226). */
class SessionStampsTest {
    private val root = TestRepos.tmpDir("stamps")
    private val userHome = root.resolve("user")
    private val configDir = root.resolve("cfg")
    private val uuid = "0b9a6c1e-1111-4222-8333-444455556666"
    private val store = SessionIdentities(root.resolve(SessionIdentities.FILE))
    private val queued = ArrayList<Runnable>()

    private fun transcript(session: String, dir: Path = configDir) = dir.resolve("projects").resolve("C--repo").resolve("$session.jsonl").toString()

    private fun hooks(config: HooksConfig = HooksConfig(), accounts: List<ClaudeAccount> = emptyList(), run: (Runnable) -> Unit = Runnable::run) = Hooks(
        Steering(FakeSources("/repo", emptyMap()), ShellPaths("/home/me", windows = false)), { config }, AppendLog(root.resolve("hooks.jsonl"))::append, { "/repo" },
        stamps = SessionStamps(store, ClaudeLogin(userHome), { accounts }, run),
    )

    private fun event(name: String, session: String, path: String?, login: String? = null, account: String? = null, token: Boolean = false) = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", session)
        put("cwd", "/repo")
        path?.let { put("transcript_path", it) }
        login?.let { put("codeloupe_login", it) }
        account?.let { put("codeloupe_account", it) }
        if (token) put("codeloupe_token_login", true)
    }

    private fun account(id: String, identity: String?, dir: Path = configDir) =
        ClaudeAccount(id, id, dir, implicit = false, identity = identity, email = "$id@example.com")

    @Test
    fun `the session's own account uuid wins over the login file, which holds whichever login was written last`() {
        LoginFiles.write(configDir.resolve(".claude.json"), "uuid-b")
        send(hooks(), event("SessionStart", "s1", transcript("s1"), login = uuid))
        assertEquals(AccountIdentity.fingerprint(uuid), store.all()["s1"])
        assertFalse(uuid in Files.readString(root.resolve(SessionIdentities.FILE)))
    }

    @Test
    fun `a launcher's account gives the identity accounts json declares for it, by id or e-mail`() {
        LoginFiles.write(configDir.resolve(".claude.json"), "uuid-b")
        val accounts = listOf(account("a", "aaaaaaaaaaaa"), account("b", "bbbbbbbbbbbb"), account("c", null))
        val hooks = hooks(accounts = accounts)
        send(hooks, event("SessionStart", "s1", transcript("s1"), account = "a", token = true))
        send(hooks, event("SessionStart", "s2", transcript("s2"), account = "B@Example.com", token = true))
        send(hooks, event("SessionStart", "s3", transcript("s3"), account = "c"))
        send(hooks, event("SessionStart", "s4", transcript("s4"), account = "nobody"))
        assertEquals(mapOf("s1" to "aaaaaaaaaaaa", "s2" to "bbbbbbbbbbbb"), store.all())
    }

    @Test
    fun `a session with a token of its own, or on a directory two accounts share, is not given the login file`() {
        LoginFiles.write(configDir.resolve(".claude.json"), "uuid-b")
        send(hooks(), event("SessionStart", "token", transcript("token"), token = true))
        send(hooks(accounts = listOf(account("a", null), account("b", null))), event("SessionStart", "shared", transcript("shared")))
        assertTrue(store.all().isEmpty())

        send(hooks(accounts = listOf(account("a", null), account("b", null, root.resolve("cfg-b")))), event("SessionStart", "own", transcript("own")))
        assertEquals(AccountIdentity.fingerprint("uuid-b"), store.all()["own"])
    }

    private fun send(hooks: Hooks, json: JsonObject) = runBlocking { hooks.reply(json) }

    @Test
    fun `a session start notes the fingerprint of the login of its config directory, never the uuid or the e-mail`() {
        LoginFiles.write(configDir.resolve(".claude.json"), uuid)
        send(hooks(), event("SessionStart", "s1", transcript("s1")))

        assertEquals(mapOf("s1" to AccountIdentity.fingerprint(uuid)), store.all())
        val stored = Files.readString(root.resolve(SessionIdentities.FILE))
        assertFalse(uuid in stored || LoginFiles.EMAIL in stored || "org-1" in stored, stored)
    }

    @Test
    fun `the config directory comes from the transcript path, so two logins in two directories are told apart`() {
        val other = root.resolve("cfg-b")
        LoginFiles.write(configDir.resolve(".claude.json"), "uuid-a")
        LoginFiles.write(other.resolve(".claude.json"), "uuid-b")
        val hooks = hooks()
        send(hooks, event("SessionStart", "s1", transcript("s1")))
        send(hooks, event("SessionStart", "s2", transcript("s2", other)))
        assertEquals(mapOf("s1" to AccountIdentity.fingerprint("uuid-a"), "s2" to AccountIdentity.fingerprint("uuid-b")), store.all())
    }

    @Test
    fun `the default directory reads the login beside it`() {
        LoginFiles.write(userHome.resolve(".claude.json"), uuid)
        send(hooks(), event("SessionStart", "s1", transcript("s1", userHome.resolve(".claude"))))
        assertEquals(AccountIdentity.fingerprint(uuid), store.all()["s1"])
    }

    @Test
    fun `a resumed session with another login is stamped again`() {
        val login = LoginFiles.write(configDir.resolve(".claude.json"), "uuid-a")
        val hooks = hooks()
        send(hooks, event("SessionStart", "s1", transcript("s1")))
        LoginFiles.write(login, "uuid-b-longer")
        Files.setLastModifiedTime(login, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000))
        send(hooks, event("SessionStart", "s1", transcript("s1")))
        assertEquals(AccountIdentity.fingerprint("uuid-b-longer"), store.all()["s1"])
    }

    @Test
    fun `a subagent adds nothing for a session that is stamped, and stamps one that began before the daemon`() {
        LoginFiles.write(configDir.resolve(".claude.json"), "uuid-a")
        val hooks = hooks()
        send(hooks, event("SessionStart", "s1", transcript("s1")))
        val size = Files.size(root.resolve(SessionIdentities.FILE))
        send(hooks, event("SubagentStart", "s1", transcript("s1")))
        assertEquals(size, Files.size(root.resolve(SessionIdentities.FILE)))

        send(hooks, event("SubagentStart", "s2", transcript("s2")))
        assertEquals(AccountIdentity.fingerprint("uuid-a"), store.all()["s2"])
    }

    @Test
    fun `nothing is written while the hooks are off, but the stamp does not depend on the session context`() {
        LoginFiles.write(configDir.resolve(".claude.json"), "uuid-a")
        send(hooks(HooksConfig(enabled = false)), event("SessionStart", "off", transcript("off")))
        send(hooks(HooksConfig(enabled = false)), event("SubagentStart", "off", transcript("off")))
        send(hooks(HooksConfig(stampAccount = false)), event("SessionStart", "flag", transcript("flag")))
        assertTrue(store.all().isEmpty())

        send(hooks(HooksConfig(sessionStart = HooksConfig.SessionStartConfig(enabled = false))), event("SessionStart", "no-context", transcript("no-context")))
        assertEquals(setOf("no-context"), store.all().keys)
    }

    @Test
    fun `a payload that cannot be placed, an unknown login or a failing store changes nothing and fails nothing`() {
        val hooks = hooks()
        send(hooks, event("SessionStart", "s1", null))
        send(hooks, event("SessionStart", "s2", "relative/projects/p/s2.jsonl"))
        send(hooks, event("SessionStart", "s3", "//server/share/projects/p/s3.jsonl"))
        send(hooks, event("SessionStart", "s4", root.resolve("elsewhere").resolve("s4.jsonl").toString()))
        send(hooks, event("SessionStart", "bad id/../x", transcript("s5")))
        send(hooks, event("SessionStart", "s6", transcript("s6")))
        assertTrue(store.all().isEmpty(), "no .claude.json, no stamp")

        val throwing = hooks(run = { error("pool is gone") })
        LoginFiles.write(configDir.resolve(".claude.json"), "uuid-a")
        send(throwing, event("SessionStart", "s7", transcript("s7")))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `the reply does not wait for the stamp`() {
        LoginFiles.write(configDir.resolve(".claude.json"), "uuid-a")
        val hooks = hooks(run = { queued += it })
        send(hooks, event("SessionStart", "s1", transcript("s1")))
        assertTrue(store.all().isEmpty(), "the work is only queued while the hook answers")
        queued.single().run()
        assertEquals(setOf("s1"), store.all().keys)
    }

    @Test
    fun `the flag is on by default and turned off by hooks stampAccount`() {
        fun parse(json: String) = HooksConfig.parse(kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject)
        assertTrue(parse("{}").stampAccount)
        assertTrue(parse("""{"hooks":{"steer":{"mode":"off"}}}""").stampAccount)
        assertFalse(parse("""{"hooks":{"stampAccount":false}}""").stampAccount)
        assertFalse(parse("""{"hooks":false}""").enabled)
    }

    @Test
    fun `the config directory is read from the text of a transcript path`() {
        val file = configDir.resolve("projects").resolve("C--repo").resolve("s.jsonl")
        assertEquals(configDir, SessionConfigDir.of(file.toString()))
        assertEquals(configDir, SessionConfigDir.of(configDir.resolve("projects").resolve("C--repo").resolve("s").resolve("subagents").resolve("agent-1.jsonl").toString()))
        assertNull(SessionConfigDir.of(configDir.resolve("projects").resolve("C--repo").resolve("s.txt").toString()))
        assertNull(SessionConfigDir.of(configDir.resolve("notes").resolve("s.jsonl").toString()))
        assertNull(SessionConfigDir.of("\\\\server\\share\\projects\\p\\s.jsonl"))
        assertNull(SessionConfigDir.of("projects/p/s.jsonl"))
    }
}
