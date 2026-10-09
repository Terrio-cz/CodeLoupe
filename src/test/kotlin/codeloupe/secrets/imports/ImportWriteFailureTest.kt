package codeloupe.secrets.imports

import codeloupe.TestRepos
import codeloupe.config.EnvImportConfig
import codeloupe.config.ImportRoot
import codeloupe.secrets.PassphraseProtector
import codeloupe.secrets.SecretStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A source file that refuses the rewrite must not abort the import and lose the id of the backup of the files already rewritten. */
class ImportWriteFailureTest {
    private val base = TestRepos.tmpDir("import-fail")
    private val repos = base.resolve("repos")
    private val ownHome = repos.resolve(".codeloupe-home")
    private val store = SecretStore(ownHome.resolve("secrets").resolve("vault.env"), PassphraseProtector("pw".toCharArray(), iterations = 1_000))
    private val backups = ImportBackups(ownHome.resolve("secrets").resolve("import-backups"), store)
    private val config = EnvImportConfig(listOf(ImportRoot(repos, ImportRoot.Kind.REPOSITORIES)), exclude = emptyList())
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

    private fun put(relative: String, text: String): Path = repos.resolve(relative).also { it.parent.createDirectories(); it.writeText(text) }

    // A file the rewrite cannot replace: read-only on Windows, in a read-only folder elsewhere.
    private fun lock(file: Path) {
        if (windows) file.toFile().setReadOnly() else file.parent.toFile().setWritable(false)
        assumeTrue(windows || !Files.isWritable(file.parent), "this user can write anywhere")
    }

    private fun unlock(file: Path) {
        file.toFile().setWritable(true)
        file.parent.toFile().setWritable(true)
    }

    @Test
    fun `a file that cannot be rewritten is reported, the others are replaced and the backup can be rolled back`() {
        put("app/.git/HEAD", "ref: refs/heads/main\n")
        val first = put("app/a/.env", "FIRST_TOKEN=fake-first-token-1111\n")
        val second = put("app/b/.env", "SECOND_TOKEN=fake-second-token-2222\n")
        val before = Files.readAllBytes(second)
        val scan = EnvScanner(config, ownHome).scan()
        lock(second)
        try {
            val result = EnvImporter(store, backups).run(scan, scan.found.map { ImportSelection(it.id) }, replaceSources = true)

            assertEquals(listOf(second.toString()), result.notReplaced.map { it.file })
            assertTrue(result.notReplaced.single().reason.startsWith("cannot be written"), result.notReplaced.single().reason)
            assertEquals(1, result.replacedFiles)
            val backupId = assertNotNull(result.backupId, "the files already rewritten must stay reachable by their backup")
            assertContentEquals(before, Files.readAllBytes(second))

            unlock(second)
            val rolled = backups.rollback(backupId)
            assertTrue(rolled.complete)
            assertEquals(1, rolled.restored)
            assertEquals("FIRST_TOKEN=fake-first-token-1111\n", Files.readString(first))
        } finally {
            unlock(second)
        }
    }

    @Test
    fun `a rollback that cannot write one file reports it and keeps the copies`() {
        put("app/.git/HEAD", "ref: refs/heads/main\n")
        val only = put("app/a/.env", "ONLY_TOKEN=fake-only-token-3333\n")
        val scan = EnvScanner(config, ownHome).scan()
        val backupId = assertNotNull(EnvImporter(store, backups).run(scan, scan.found.map { ImportSelection(it.id) }, replaceSources = true).backupId)
        lock(only)
        try {
            val rolled = backups.rollback(backupId)
            assertEquals(listOf(only.toString()), rolled.failed)
            assertTrue(!rolled.complete)
            assertEquals(listOf(backupId), backups.list().map { it.id }, "the copy is the only way back")

            unlock(only)
            assertTrue(backups.rollback(backupId).complete)
            assertEquals("ONLY_TOKEN=fake-only-token-3333\n", Files.readString(only))
        } finally {
            unlock(only)
        }
    }
}
