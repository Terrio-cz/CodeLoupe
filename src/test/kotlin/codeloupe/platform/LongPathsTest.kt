package codeloupe.platform

import codeloupe.TestRepos
import codeloupe.index.Store
import codeloupe.query.View
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Paths past Windows' MAX_PATH under a deep CodeLoupe home (CL-253). */
class LongPathsTest {
    private val deep = "d".repeat(100)

    @Test
    fun `a long database path on Windows goes to SQLite as an encoded extended-length URI, a short one as it is`() {
        val elsewhere = Path.of("h", deep, deep, "x.db").toAbsolutePath()
        assertEquals("jdbc:sqlite:$elsewhere", LongPaths.sqliteUrl(elsewhere, windows = false))
        assumeTrue(NativeCalls.isWindows, "Windows paths")
        val short = Path.of("C:\\h\\repos\\x.db")
        assertEquals("C:\\h\\repos\\x.db", LongPaths.sqliteName(short, windows = true))
        assertEquals("jdbc:sqlite:C:\\h\\repos\\x.db", LongPaths.sqliteUrl(short, windows = true))

        val long = "C:\\h\\$deep\\$deep\\$deep\\a%b#c.db"
        val name = LongPaths.sqliteName(Path.of(long), windows = true)
        assertEquals("file:%5C%5C%3F%5CC:%5Ch%5C$deep%5C$deep%5C$deep%5Ca%25b%23c.db", name)
        val unc = LongPaths.sqliteName(Path.of("\\\\server\\share\\$deep\\$deep\\$deep\\x.db"), windows = true)
        assertEquals("file:%5C%5C%3F%5CUNC%5Cserver%5Cshare%5C$deep%5C$deep%5C$deep%5Cx.db", unc)
    }

    @Test
    fun `a base index and an overlay past MAX_PATH open, take writes and attach`() {
        val dir = Files.createDirectories(TestRepos.tmpDir("long-db").resolve(deep).resolve(deep))
        val base = dir.resolve("0123456789012345678901234567890123456789.db")
        val overlay = dir.resolve("overlays").resolve("0123456789ab.db").also { Files.createDirectories(it.parent) }
        assertTrue(base.toString().length > 260, base.toString())
        for (file in listOf(base, overlay)) Store.open(file).use { db ->
            db.createStatement().use { it.execute("INSERT INTO meta(key, value) VALUES('k', 'v')") }
            Store.checkpoint(db)
        }
        assertTrue(Files.exists(base) && Files.exists(overlay))
        View(base, overlay).close()
        Sqlite.open(dir.resolve("jobs.db"), listOf("CREATE TABLE IF NOT EXISTS t(x)")).use { db -> db.createStatement().use { it.execute("INSERT INTO t VALUES(1)") } }
    }

    @Test
    fun `a process whose directory is past the limit starts in the temporary directory`() {
        val dir = Files.createDirectories(TestRepos.tmpDir("long-cwd").resolve(deep).resolve(deep).resolve(deep))
        assertEquals(dir, LongPaths.processDir(dir, windows = false))
        val used = LongPaths.processDir(dir, windows = true)
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(), used)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, "-version").directory(LongPaths.processDir(dir).toFile())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }
}
