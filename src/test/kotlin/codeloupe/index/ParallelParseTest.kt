package codeloupe.index

import codeloupe.TestRepos
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Several parser threads give the facts and the store that one thread gives (CL-247): the parser shares one environment. */
class ParallelParseTest {
    private fun <T> withThreads(count: Int, block: () -> T): T {
        Extraction.useThreads(count)
        try {
            return block()
        } finally {
            Extraction.useThreads(1)
        }
    }

    @Test
    fun `this project's own sources parse to the same facts on four threads as one at a time`() {
        val root = Path.of(System.getProperty("codeloupe.projectDir"), "src")
        val files = root.walk().filter { it.isRegularFile() && it.extension in setOf("kt", "kts", "java") }.sorted()
            .map { ParseFile(root.relativize(it).toString().replace('\\', '/'), it.readText()) }.toList()
        assertTrue(files.size > 500, "a corpus large enough to race: ${files.size}")
        val alone = files.map { Extraction.parseHere(it.path, it.text) }
        val together = withThreads(4) { files.chunked(64).flatMap { Extraction.parseAllHere(it) } }
        for (i in files.indices) assertEquals(alone[i], together[i], files[i].path)
    }

    @Test
    fun `a base built with four parser threads is row for row the one built with one`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
        val commit = TestRepos.git(repo, "rev-parse", "HEAD")
        val dir = TestRepos.tmpDir("parallel-base")
        val one = dir.resolve("one.db").also { BaseBuilder.build(repo.toString(), commit, it) }
        val four = dir.resolve("four.db").also { withThreads(4) { BaseBuilder.build(repo.toString(), commit, it) } }
        for (table in listOf("files", "imports", "decls", "refs")) assertEquals(rows(one, table), rows(four, table), table)
    }

    /** Every row of [table] with its ids, in id order: the same order of writes gives the same ids. */
    private fun rows(db: Path, table: String): List<String> = Store.open(db, readOnly = true).use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT * FROM $table ORDER BY rowid").use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { rs.getString(it).orEmpty() }) }
            }
        }
    }.also { assertTrue(it.isNotEmpty(), "$table has rows") }
}
