package codeloupe.index

import codeloupe.TestRepos
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Facts copied file by file from one store into another equal the original in every column but the row ids. */
class StoreCopierTest {
    @Test
    fun `copied files keep every column, ids remapped`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
        val dir = TestRepos.tmpDir("copy")
        val source = dir.resolve("source.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), source)
        val target = dir.resolve("target.db")
        Store.open(source, readOnly = true).use { from ->
            Store.open(target).use { to ->
                // An unrelated row first, so ids differ between the stores.
                to.createStatement().use { it.execute("INSERT INTO decls(file_id, kind, name, container, fqn) VALUES(0, 'fun', 'x', '', 'x')") }
                val files = from.createStatement().use { s -> s.executeQuery("SELECT path, size, mtime FROM files").use { rs -> buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getLong(2), rs.getLong(3))) } } }
                StoreCopier(from, to).use { copier -> files.forEach { (path, size, mtime) -> assertTrue(copier.copy(path, size, mtime)) } }
                to.createStatement().use { it.execute("DELETE FROM decls WHERE file_id = 0") }
            }
        }
        for (table in listOf("files", "imports", "decls", "refs")) assertEquals(rows(source, table), rows(target, table), table)
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
