package codeloupe.index

import codeloupe.TestRepos
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A file whose content a store already holds is not parsed again: its facts are copied, and equal what a parse gives. */
class FactSourcesTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
    private val dir = TestRepos.tmpDir("fact-sources")
    private val source = dir.resolve("source.db").also { BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), it) }
    private val path = "src/main/kotlin/com/example/shop/Constructs.kt"
    private val text = Files.readString(repo.resolve(path))

    private fun put(target: Path, sources: List<String>, content: String = text): BuildResult {
        val file = dir.resolve("work-${target.fileName}.kt").also { Files.writeString(it, content) }
        val update = StoreUpdate(puts = listOf(FilePut(path, file = file.toString(), size = content.length.toLong(), mtime = 7)), factSources = sources)
        return Store.open(target).use { StoreUpdater.apply(it, update, null) }
    }

    @Test
    fun `the same content is copied from a source store, facts equal a parse, nothing is parsed`() {
        val parsed = dir.resolve("parsed.db")
        val before = Extraction.parsed.get()
        assertEquals(0, put(parsed, emptyList()).reused)
        assertEquals(before + 1, Extraction.parsed.get())

        val reused = dir.resolve("reused.db")
        val result = put(reused, listOf(source.toString()))
        assertEquals(1, result.reused)
        assertEquals(1, result.files)
        assertEquals(before + 1, Extraction.parsed.get(), "no second parse")
        for (table in listOf("files", "imports", "decls", "refs")) assertEquals(rows(parsed, table), rows(reused, table), table)
    }

    @Test
    fun `other content, a missing source and a source of another format are parsed or skipped`() {
        val before = Extraction.parsed.get()
        val edited = put(dir.resolve("edited.db"), listOf(source.toString()), content = "$text\nfun extra() = 1\n")
        assertEquals(0, edited.reused, "a changed text has other facts")
        assertEquals(before + 1, Extraction.parsed.get())

        val foreign = dir.resolve("foreign.db")
        Files.copy(source, foreign)
        Store.open(foreign).use { Store.setMeta(it, "format", "0/other") }
        val skipped = put(dir.resolve("skipped.db"), listOf(dir.resolve("missing.db").toString(), foreign.toString()))
        assertEquals(0, skipped.reused)
        assertTrue(skipped.ok)
        assertEquals(before + 2, Extraction.parsed.get())
    }

    /** Every column of [table] as text, ids replaced by what they point at, sorted. */
    private fun rows(db: Path, table: String): List<String> = Store.open(db, readOnly = true).use { c ->
        val paths = map(c, "SELECT id, path FROM files")
        val decls = map(c, "SELECT id, fqn || '@' || start_line FROM decls")
        c.createStatement().use { s ->
            s.executeQuery("SELECT * FROM $table").use { rs ->
                val meta = rs.metaData
                buildList {
                    while (rs.next()) {
                        add(
                            (1..meta.columnCount).mapNotNull { i ->
                                val value = rs.getString(i)
                                when (meta.getColumnName(i)) {
                                    "id" -> null
                                    "file_id" -> paths[value]
                                    "decl_id", "parent_id" -> value?.let { decls[it] }
                                    else -> value
                                }
                            }.joinToString("|"),
                        )
                    }
                }.sorted()
            }
        }
    }

    private fun map(c: Connection, sql: String): Map<String, String> =
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } } }
}
