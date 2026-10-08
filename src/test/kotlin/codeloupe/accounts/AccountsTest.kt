package codeloupe.accounts

import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.secrets.PassphraseProtector
import codeloupe.secrets.SecretScope
import codeloupe.secrets.SecretStore
import codeloupe.tracker.TokenSource
import codeloupe.tracker.TrackerException
import codeloupe.tracker.TrackerSettingsLoader
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountsTest {
    private val home = TestRepos.tmpDir("accounts-home")
    private val userHome = TestRepos.tmpDir("accounts-user")
    private val accounts = Accounts(home, userHome)

    private fun write(file: AccountsFile) = home.resolve(AccountsFile.FILE).writeText(JsonFormat.json.encodeToString(AccountsFile.serializer(), file))

    @Test
    fun `without a file the default Claude directory is the one account and adds no transcript directory`() {
        val only = accounts.claude().single()
        assertEquals(Accounts.DEFAULT_ID, only.id)
        assertEquals(userHome.resolve(".claude"), only.configDir)
        assertTrue(only.isDefault && only.implicit)
        userHome.resolve(".claude").resolve("projects").resolve("p").createDirectories()
        assertEquals(emptyList(), accounts.transcriptDirs(), "the ingest reads ~/.claude/projects on its own")
        assertEquals(emptyList(), accounts.youtrack())
    }

    @Test
    fun `listed accounts keep their order, the first is the default unless one says so, and their projects are ingested`() {
        val b = TestRepos.tmpDir("claude-b")
        b.resolve("projects").resolve("one").createDirectories()
        b.resolve("projects").resolve("two").createDirectories()
        Files.writeString(b.resolve("projects").resolve("not-a-dir.txt"), "x")
        write(AccountsFile(claude = listOf(AccountsFile.Claude("a", "Účet A", userHome.resolve(".claude").toString()), AccountsFile.Claude("b", "Účet B", b.toString()))))
        assertEquals(listOf("a", "b"), accounts.claude().map { it.id })
        assertEquals(listOf(true, false), accounts.claude().map { it.isDefault })
        assertEquals(setOf("one", "two"), accounts.transcriptDirs().map { it.fileName.toString() }.toSet())
        write(AccountsFile(claude = listOf(AccountsFile.Claude("a", "Účet A", "C:/a"), AccountsFile.Claude("b", "Účet B", b.toString(), default = true))))
        assertEquals(listOf(false, true), accounts.claude().map { it.isDefault })
        assertTrue(accounts.claude().none { it.implicit })
    }

    @Test
    fun `a damaged file is no accounts, not a failure`() {
        home.resolve(AccountsFile.FILE).writeText("{ not json")
        assertEquals(AccountsFile(), AccountsFile.read(home))
        assertEquals(1, accounts.claude().size)
    }

    @Test
    fun `the e-mail of an account comes from its own dot claude json and nothing else is read`() {
        val dir = TestRepos.tmpDir("claude-mail")
        dir.resolve(".claude.json").writeText("""{"oauthAccount":{"emailAddress":"dev@example.test","organizationName":"x"},"projects":{"c:/x":{}}}""")
        val account = ClaudeAccount("m", "M", dir, isDefault = false, implicit = false)
        val profiles = ClaudeProfiles(userHome)
        assertEquals("dev@example.test", profiles.email(account))
        dir.resolve(".claude.json").writeText("""{"oauthAccount":{"emailAddress":"not-an-address"}}""")
        Files.setLastModifiedTime(dir.resolve(".claude.json"), FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        assertNull(profiles.email(account), "re-read after the file changed, and a value without @ is no e-mail")
        assertNull(profiles.email(ClaudeAccount("z", "Z", TestRepos.tmpDir("claude-none"), isDefault = false, implicit = false)))
        userHome.resolve(".claude.json").writeText("""{"oauthAccount":{"emailAddress":"home@example.test"}}""")
        assertEquals("home@example.test", profiles.email(ClaudeAccount("d", "D", userHome.resolve(".claude"), isDefault = true, implicit = true)), "~/.claude is described by ~/.claude.json")
    }

    @Test
    fun `a working directory belongs to the account that has a transcript folder for it, the most recent one when several do`() {
        val a = TestRepos.tmpDir("claude-a")
        val b = TestRepos.tmpDir("claude-b2")
        val root = "C:\\Users\\dev\\Documents\\Claude\\terrio"
        assertEquals("C--Users-dev-Documents-Claude-terrio", ProjectDirName.of(root))
        assertEquals("-work-app", ProjectDirName.of("/work/app/"))
        val accountsList = listOf(ClaudeAccount("a", "A", a, isDefault = true, implicit = false), ClaudeAccount("b", "B", b, isDefault = false, implicit = false))
        val roots = AccountRoots(accountsList)
        assertNull(roots.accountOf(root))
        val inA = a.resolve("projects").resolve(ProjectDirName.of(root)).createDirectories()
        assertEquals("a", roots.accountOf(root))
        val inB = b.resolve("projects").resolve(ProjectDirName.of(root)).createDirectories()
        Files.setLastModifiedTime(inA, FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(inB, FileTime.fromMillis(2_000_000))
        assertEquals("b", roots.accountOf(root))
    }

    @Test
    fun `YouTrack accounts become tracker instances whose token is a name in the store, and config json wins a clash`() {
        val vault = home.resolve("secrets").resolve("vault.env")
        val store = SecretStore(vault, PassphraseProtector("pw".toCharArray(), iterations = 1_000))
        store.set("YOUTRACK_TOKEN_TERRIO", SecretScope.GLOBAL, "fake-yt-token-8812")
        write(
            AccountsFile(
                youtrack = listOf(
                    AccountsFile.Youtrack("terrio", "Terrio", "https://terrio.youtrack.cloud/", listOf("ter", "CL"), "YOUTRACK_TOKEN_TERRIO"),
                    AccountsFile.Youtrack("bad", null, "https://user:pw@x.example", listOf("X"), "T"),
                    AccountsFile.Youtrack("empty", null, "https://e.example", emptyList(), "T"),
                    AccountsFile.Youtrack("clash", null, "https://clash.example", listOf("C"), "T"),
                ),
            ),
        )
        home.resolve("config.json").writeText("""{"trackers":[{"name":"clash","url":"https://other.example","projects":["O"],"token":{"env":"X"}}]}""")
        val settings = TrackerSettingsLoader.load(home, { store }) { mapOf("X" to "env-value") }
        assertEquals(listOf("clash", "terrio"), settings.instances.map { it.name })
        assertEquals("https://other.example", settings.instances.first().url)
        val terrio = settings.instances.last()
        assertEquals(listOf("TER", "CL"), terrio.projects)
        assertEquals("https://terrio.youtrack.cloud", terrio.url)
        assertEquals("fake-yt-token-8812", terrio.token.read())
        assertEquals(3, settings.problems.size, settings.problems.toString())
        assertFalse(settings.toString().contains("fake-yt-token-8812") || settings.problems.joinToString().contains("pw"))
    }

    @Test
    fun `a stored token is read from the store at most every cache interval and its absence is said without a value`() {
        val vault = home.resolve("secrets").resolve("vault.env")
        val store = SecretStore(vault, PassphraseProtector("pw".toCharArray(), iterations = 1_000))
        var now = 0L
        val source = TokenSource.Stored("T_ONE", { store }, "tracker mirror: t", cacheMs = 1_000, now = { now })
        assertEquals("token T_ONE is not in the secret store", assertFailsWith<TrackerException> { source.read() }.message)
        store.set("T_ONE", SecretScope.GLOBAL, "first-fake-token")
        assertEquals("first-fake-token", source.read())
        store.set("T_ONE", SecretScope.GLOBAL, "second-fake-token")
        now = 500
        assertEquals("first-fake-token", source.read(), "within the cache interval")
        now = 1_500
        assertEquals("second-fake-token", source.read(), "a rotated token applies after it")
        assertEquals("store T_ONE", source.toString())
        assertEquals(listOf("tracker mirror: t"), store.list().single().usedBy)
        assertEquals("no secret store to read T_ONE from", assertFailsWith<TrackerException> { TokenSource.Stored("T_ONE", { null }, "x").read() }.message)
    }
}
