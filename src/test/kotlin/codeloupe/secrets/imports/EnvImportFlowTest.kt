package codeloupe.secrets.imports

import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.cli.EnvImportCommand
import codeloupe.config.EnvImportConfig
import codeloupe.config.ImportRoot
import codeloupe.secrets.PassphraseProtector
import codeloupe.secrets.SecretScope
import codeloupe.secrets.SecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The import on fixture trees that mimic the Claude folders; every value is an obvious fake. */
class EnvImportFlowTest {
    private val values = mapOf(
        "ANTHROPIC_API_KEY" to "fake-anthropic-key-1111",
        "YOUTRACK_TOKEN" to "fake-youtrack-token-2222",
        "TERRIO_API_KEY" to "fake-terrio-key-3333",
        "DB_PASSWORD" to "fake-db-password-4444",
        "API_KEY" to "fake-api-key-5555",
        "API_KEY_OTHER" to "fake-api-key-other-6666",
        "POSTGRES_PASSWORD" to "fake-pg-password-7777",
        "HIDDEN" to "fake-hidden-8888",
        "TNT_SECRET" to "fake-tnt-secret-9999",
        "OWN_SECRET" to "fake-own-secret-0000",
    )

    private inner class Fixture {
        val base: Path = TestRepos.tmpDir("import")
        val home = base.resolve("user")
        val docs = base.resolve("docs").resolve("Claude")
        val repos = base.resolve("repos")
        val ownHome = repos.resolve(".codeloupe-home")
        val originals = mutableMapOf<Path, ByteArray>()
        val store = SecretStore(ownHome.resolve("secrets").resolve("vault.env"), PassphraseProtector("pw".toCharArray(), iterations = 1_000))
        val backups = ImportBackups(ownHome.resolve("secrets").resolve("import-backups"), store)
        val config = EnvImportConfig(
            listOf(
                ImportRoot(home.resolve(".claude"), ImportRoot.Kind.HOME), ImportRoot(home.resolve(".claude.json"), ImportRoot.Kind.HOME),
                ImportRoot(docs, ImportRoot.Kind.WORKSPACES), ImportRoot(repos, ImportRoot.Kind.REPOSITORIES),
            ),
        )

        init {
            put(home.resolve(".claude").resolve("settings.json"), "{\n  \"model\": \"x\",\n  \"env\": {\n    \"ANTHROPIC_API_KEY\": \"${values["ANTHROPIC_API_KEY"]}\",\n    \"BASH_DEFAULT_TIMEOUT_MS\": \"64000\"\n  }\n}\n")
            put(home.resolve(".claude.json"), "{\"numStartups\":3,\"projects\":{\"c:/w\":{\"mcpServers\":{\"yt\":{\"type\":\"stdio\",\"env\":{\"YOUTRACK_TOKEN\":\"${values["YOUTRACK_TOKEN"]}\"}}}}}}")
            put(docs.resolve("terrio").resolve(".mcp.json"), "{\"mcpServers\":{\"yt\":{\"command\":\"node\",\"env\":{\"YOUTRACK_TOKEN\":\"${values["YOUTRACK_TOKEN"]}\"}}}}\n")
            put(docs.resolve("terrio").resolve(".claude").resolve("settings.local.json"), "{\"env\":{\"YOUTRACK_TOKEN\":\"${values["YOUTRACK_TOKEN"]}\"}}\n")
            put(docs.resolve("terrio").resolve(".env"), "TERRIO_API_KEY=\"${values["TERRIO_API_KEY"]}\"\r\nEMPTY_ONE=\r\nREF=\${TERRIO_API_KEY}\r\nPLAIN_SETTING=on\r\n")
            put(repos.resolve("app").resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n")
            put(repos.resolve("app").resolve(".env"), "DB_PASSWORD=${values["DB_PASSWORD"]}\nAPI_KEY=${values["API_KEY"]}\n")
            put(repos.resolve("app").resolve("service").resolve(".env"), "API_KEY=${values["API_KEY_OTHER"]}\n")
            put(repos.resolve("app").resolve(".env.example"), "DB_PASSWORD=changeme\n")
            put(repos.resolve("app").resolve("docker").resolve("docker.env"), "POSTGRES_PASSWORD=${values["POSTGRES_PASSWORD"]}\n")
            put(repos.resolve("app").resolve("node_modules").resolve("x").resolve(".env"), "HIDDEN=${values["HIDDEN"]}\n")
            put(repos.resolve("tnt-service").resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n")
            put(repos.resolve("tnt-service").resolve(".env"), "TNT_SECRET=${values["TNT_SECRET"]}\n")
            put(repos.resolve("tnt2").resolve(".env"), "TNT_SECRET=${values["TNT_SECRET"]}\n")
            put(ownHome.resolve("stray.env"), "OWN_SECRET=${values["OWN_SECRET"]}\n")
        }

        private fun put(file: Path, text: String) {
            file.parent.createDirectories()
            file.writeBytes(text.toByteArray())
            originals[file] = text.toByteArray()
        }

        fun scan() = EnvScanner(config, ownHome).scan()

        fun importer() = EnvImporter(store, backups)

        fun sensitive(scan: EnvScanner.Result) = scan.found.filter { it.sensitive }.map { ImportSelection(it.id) }

        fun file(vararg parts: String): Path = parts.fold(base) { dir, part -> dir.resolve(part) }

        /** Every file under [dir] (the vault, the backups) read as bytes, so no stored or copied value may appear in it. */
        fun assertNoValueIn(dir: Path) = Files.walk(dir).filter { Files.isRegularFile(it) }.forEach { file ->
            val text = String(Files.readAllBytes(file), Charsets.ISO_8859_1)
            values.forEach { (name, value) -> assertFalse(value in text, "$name in $file") }
        }
    }

    private fun assertNoValue(label: String, text: String) = values.forEach { (name, value) -> assertFalse(value in text, "$name in $label") }

    @Test
    fun `the scan finds every source kind, leaves out templates, build folders, the daemon home and excluded systems`() {
        val f = Fixture()
        val scan = f.scan()
        val byName = scan.found.groupBy { it.name }
        assertEquals(
            setOf("ANTHROPIC_API_KEY", "BASH_DEFAULT_TIMEOUT_MS", "YOUTRACK_TOKEN", "TERRIO_API_KEY", "PLAIN_SETTING", "DB_PASSWORD", "API_KEY", "POSTGRES_PASSWORD"),
            byName.keys,
        )
        assertEquals(setOf(SourceKind.CLAUDE_JSON, SourceKind.MCP_CONFIG, SourceKind.CLAUDE_SETTINGS), byName.getValue("YOUTRACK_TOKEN").map { it.kind }.toSet())
        assertEquals(SourceKind.DOCKER_ENV, byName.getValue("POSTGRES_PASSWORD").single().kind)
        assertEquals(SourceKind.DOTENV, byName.getValue("DB_PASSWORD").single().kind)
        assertEquals(setOf("tnt-service", "tnt2"), scan.excluded.map { it.fileName.toString() }.toSet())
        assertEquals(1, scan.empty)
        assertEquals(1, scan.references)
        assertEquals(8, scan.filesRead)
        val withExcluded = EnvScanner(f.config, f.ownHome, includeExcluded = true).scan()
        assertTrue("TNT_SECRET" in withExcluded.found.map { it.name })
        assertTrue(withExcluded.excluded.isEmpty())
    }

    @Test
    fun `scopes follow the folder the file is in`() {
        val scan = Fixture().scan()
        fun scopeOf(name: String, kind: SourceKind? = null) = scan.found.filter { it.name == name && (kind == null || it.kind == kind) }.map { it.scope.toString() }.toSet()
        assertEquals(setOf("global"), scopeOf("ANTHROPIC_API_KEY"))
        assertEquals(setOf("global"), scopeOf("YOUTRACK_TOKEN", SourceKind.CLAUDE_JSON))
        assertTrue(scopeOf("TERRIO_API_KEY").single().startsWith("workspace:") && scopeOf("TERRIO_API_KEY").single().endsWith("/claude/terrio"))
        assertTrue(scopeOf("DB_PASSWORD").single().startsWith("repo:") && scopeOf("DB_PASSWORD").single().endsWith("/repos/app"))
        assertEquals(scopeOf("DB_PASSWORD"), scopeOf("API_KEY"), "a file in a subfolder of a repository belongs to the repository")
    }

    @Test
    fun `the inventory reports names, sources, duplicates and conflicts by hash and no value anywhere`() {
        val f = Fixture()
        val scan = f.scan()
        val report = InventoryBuilder.build(scan, f.config.roots, store = null)
        val c = report.counts
        assertEquals(9, c.groups)
        assertEquals(11, c.occurrences)
        assertEquals(1, c.duplicates)
        assertEquals(1, c.conflicts)
        assertEquals(1, c.references)
        assertEquals(2, c.excludedFolders)
        val ytWorkspace = report.variables.single { it.name == "YOUTRACK_TOKEN" && it.scope.startsWith("workspace:") }
        assertTrue(ytWorkspace.duplicate && !ytWorkspace.conflict)
        assertEquals(1, ytWorkspace.sources.map { it.hash }.distinct().size)
        val api = report.variables.single { it.name == "API_KEY" }
        assertTrue(api.conflict && !api.duplicate)
        assertEquals(2, api.sources.map { it.hash }.distinct().size)
        assertFalse(report.variables.single { it.name == "BASH_DEFAULT_TIMEOUT_MS" }.sensitive)
        assertTrue(report.variables.single { it.name == "ANTHROPIC_API_KEY" }.sensitive)
        assertTrue(report.variables.all { it.store == "new" })
        val json = JsonFormat.json.encodeToString(InventoryReport.serializer(), report)
        assertNoValue("inventory json", json)
        assertNoValue("inventory text", ImportLines.inventory(report).joinToString("\n"))
        assertNoValue("variable text", scan.found.joinToString("\n") { it.toString() })
        // The hashes of two reports differ: they only compare values within one report.
        val again = InventoryBuilder.build(scan, f.config.roots, store = null)
        assertTrue(again.variables.single { it.name == "API_KEY" }.sources.map { it.hash } != api.sources.map { it.hash })
    }

    @Test
    fun `an import stores confirmed variables, reports created updated skipped and changes nothing when rerun`() {
        val f = Fixture()
        val scan = f.scan()
        val first = f.importer().run(scan, f.sensitive(scan))
        assertEquals(6, first.created)
        assertEquals(0, first.updated)
        assertEquals(1, first.skipped, "API_KEY holds two different values in one scope: a conflict, stored from neither")
        assertEquals(2, first.items.count { it.outcome == ImportResult.Outcome.SKIPPED_CONFLICT })
        val stored = f.store.list()
        assertEquals(6, stored.size)
        assertTrue(stored.none { it.name == "API_KEY" })
        assertTrue(f.store.holds("DB_PASSWORD", SecretScope.parse(stored.single { it.name == "DB_PASSWORD" }.scope), values.getValue("DB_PASSWORD")))
        assertEquals(f.file("repos", "app", "docker", "docker.env").toString(), stored.single { it.name == "POSTGRES_PASSWORD" }.source)

        val second = f.importer().run(f.scan(), f.sensitive(f.scan()))
        assertEquals(0, second.created + second.updated)
        assertEquals(7, second.skipped)
        assertEquals(6, f.store.list().size)
        assertEquals(stored.map { it.created }, f.store.list().map { it.created }, "nothing was rewritten")
        assertTrue(f.store.list().all { it.rotated == null })

        // Choosing one of the conflicting sources resolves it.
        val chosen = f.scan().found.single { it.name == "API_KEY" && it.file.parent.fileName.toString() == "service" }
        val third = f.importer().run(f.scan(), listOf(ImportSelection(chosen.id)))
        assertEquals(1, third.created)
        assertTrue(f.store.holds("API_KEY", chosen.scope, values.getValue("API_KEY_OTHER")))

        assertNoValue("import result", JsonFormat.json.encodeToString(ImportResult.serializer(), first) + ImportLines.imported(first).joinToString("\n"))
        f.assertNoValueIn(f.ownHome.resolve("secrets"))
    }

    @Test
    fun `a stored value that differs is kept unless the files are told to win, and a selection can pick another scope`() {
        val f = Fixture()
        val scan = f.scan()
        f.importer().run(scan, f.sensitive(scan))
        val dbFile = f.file("repos", "app", ".env")
        dbFile.writeText("DB_PASSWORD=fake-db-password-ROTATED\n")
        val rescanned = f.scan()
        val db = rescanned.found.single { it.name == "DB_PASSWORD" }
        val kept = f.importer().run(rescanned, listOf(ImportSelection(db.id)))
        assertEquals(ImportResult.Outcome.SKIPPED_DIFFERS, kept.items.single().outcome)
        assertFalse(f.store.holds("DB_PASSWORD", db.scope, "fake-db-password-ROTATED"))
        assertEquals("differs", InventoryBuilder.build(rescanned, f.config.roots, f.store).variables.single { it.name == "DB_PASSWORD" }.store)
        val won = f.importer().run(rescanned, listOf(ImportSelection(db.id)), overwrite = true)
        assertEquals(1, won.updated)
        assertTrue(f.store.holds("DB_PASSWORD", db.scope, "fake-db-password-ROTATED"))
        assertNotNull(f.store.list().single { it.name == "DB_PASSWORD" }.rotated)

        val elsewhere = f.importer().run(rescanned, listOf(ImportSelection(db.id, SecretScope.GLOBAL)))
        assertEquals("global", elsewhere.items.single().scope)
        assertEquals(1, elsewhere.created)
    }

    @Test
    fun `replacing sources leaves references, keeps the rest of each file, and rollback restores every file byte for byte`() {
        val f = Fixture()
        val scan = f.scan()
        val result = f.importer().run(scan, f.sensitive(scan), replaceSources = true)
        val backupId = assertNotNull(result.backupId)
        assertEquals(7, result.replacedFiles, "settings, .claude.json, .mcp.json, settings.local.json, terrio/.env, app/.env and docker.env")
        assertTrue(result.notReplaced.isEmpty())

        val terrioEnv = String(Files.readAllBytes(f.file("docs", "Claude", "terrio", ".env")))
        assertContains(terrioEnv, "# TERRIO_API_KEY moved to the CodeLoupe store")
        assertContains(terrioEnv, "\r\nEMPTY_ONE=\r\n", message = "line endings and other lines stay")
        assertContains(terrioEnv, "PLAIN_SETTING=on\r\n")
        val settings = String(Files.readAllBytes(f.file("user", ".claude", "settings.json")))
        assertContains(settings, "\"ANTHROPIC_API_KEY\": \"\${ANTHROPIC_API_KEY}\"")
        assertContains(settings, "\"BASH_DEFAULT_TIMEOUT_MS\": \"64000\"")
        assertContains(String(Files.readAllBytes(f.file("user", ".claude.json"))), "\"numStartups\":3")
        val appEnv = String(Files.readAllBytes(f.file("repos", "app", ".env")))
        assertContains(appEnv, "# DB_PASSWORD moved")
        assertContains(appEnv, "API_KEY=${values["API_KEY"]}", message = "a conflicting variable is not imported and so not replaced")
        f.originals.forEach { (file, _) ->
            val text = String(Files.readAllBytes(file))
            if (file.fileName.toString() in setOf("settings.json", ".claude.json", ".mcp.json", "settings.local.json")) {
                values.filterKeys { it != "API_KEY" && it != "API_KEY_OTHER" }.forEach { (_, v) -> assertFalse(v in text, "value left in $file") }
            }
        }
        f.assertNoValueIn(f.ownHome.resolve("secrets"))

        // The next scan sees references, imports nothing new and replaces nothing.
        val after = f.scan()
        assertEquals(5, after.references, "the one reference the fixture had and four JSON strings the import replaced")
        val rerun = f.importer().run(after, f.sensitive(after), replaceSources = true)
        assertEquals(0, rerun.created + rerun.updated)
        assertNull(rerun.backupId)

        assertEquals(listOf(backupId), f.backups.list().map { it.id })
        val rolled = f.backups.rollback(backupId)
        assertTrue(rolled.complete)
        assertEquals(7, rolled.restored)
        f.originals.forEach { (file, bytes) -> assertContentEquals(bytes, Files.readAllBytes(file), "$file") }
        assertTrue(f.backups.list().isEmpty())
        assertTrue(f.store.list().size >= 6, "rollback restores files, the store keeps its values")
    }

    @Test
    fun `a file edited after the import is left alone by rollback unless forced`() {
        val f = Fixture()
        val scan = f.scan()
        val id = assertNotNull(f.importer().run(scan, f.sensitive(scan), replaceSources = true).backupId)
        val edited = f.file("docs", "Claude", "terrio", ".env")
        Files.writeString(edited, Files.readString(edited) + "ADDED_LATER=1\r\n")
        val partial = f.backups.rollback(id)
        assertFalse(partial.complete)
        assertEquals(listOf(edited.toString()), partial.changedSince)
        assertEquals(6, partial.restored)
        assertContains(Files.readString(edited), "ADDED_LATER=1")
        assertEquals(1, f.backups.list().size)
        val forced = f.backups.rollback(id, force = true)
        assertTrue(forced.complete)
        assertContentEquals(f.originals.getValue(edited), Files.readAllBytes(edited))
    }

    @Test
    fun `a file that changed between the scan and the import is not touched`() {
        val f = Fixture()
        val scan = f.scan()
        val target = f.file("repos", "app", "docker", "docker.env")
        target.writeText("POSTGRES_PASSWORD=${values["POSTGRES_PASSWORD"]}\nADDED=1\n")
        val result = f.importer().run(scan, f.sensitive(scan), replaceSources = true)
        assertEquals(listOf(target.toString()), result.notReplaced.map { it.file })
        assertEquals("changed since the scan", result.notReplaced.single().reason)
        assertContains(Files.readString(target), values.getValue("POSTGRES_PASSWORD"))
    }

    @Test
    fun `selecting an unknown id fails without naming anything but the id`() {
        val f = Fixture()
        val error = runCatching { f.importer().run(f.scan(), listOf(ImportSelection("deadbeef0000"))) }.exceptionOrNull()
        assertContains(error?.message.orEmpty(), "deadbeef0000")
        assertTrue(f.store.list().isEmpty())
    }

    @Test
    fun `the import command hangs under env and a backup id cannot point outside the backup folder`() {
        assertEquals(listOf("scan", "run", "rollback", "backups", "forget"), EnvImportCommand().registeredSubcommands().map { it.commandName })
        val f = Fixture()
        assertTrue(runCatching { f.backups.rollback("..") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { f.backups.forget("../x") }.exceptionOrNull() is IllegalArgumentException)
    }
}
