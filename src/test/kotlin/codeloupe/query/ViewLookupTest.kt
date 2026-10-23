package codeloupe.query

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.index.IndexedFile
import codeloupe.index.Store
import codeloupe.index.StoreWriter
import codeloupe.lang.Languages
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Path, literal and module lookups kept in memory per base answer what the SQL scans they replace answered (CL-258). */
class ViewLookupTest {
    private val changedPath = "src/main/kotlin/com/example/shop/Constructs.kt"
    private val bigPath = "big/src/main/kotlin/com/example/big/Big.kt"

    @Test
    fun `lookups match the SQL scans on the base and through an overlay`() {
        val (base, overlay) = indexes()
        val suffixes = listOf("/Constructs.kt", "/constructs.KT", ".kt", "/Big.kt", "shop/OrderService.kt", "/Nothing.kt", "_", "%.kt", "")
        val literals = listOf("class", "fun replaced", "OrderService", "no such text anywhere", "_", "%")
        for (file in listOf(null, overlay)) {
            View(base, file).use { view ->
                for (s in suffixes) assertEquals(sqlBySuffix(base, file, s), view.filesBySuffix(s), "suffix '$s', overlay ${file != null}")
                for (l in literals) for (limit in listOf(1, 3, 100)) {
                    assertEquals(sqlContaining(base, file, l, limit), view.filesContaining(l, limit), "literal '$l' limit $limit, overlay ${file != null}")
                }
                assertEquals(sqlModules(base, file), view.modules())
            }
        }
        // A second view of the same base reads the kept lists: the answers stay the same.
        View(base, overlay).use { view -> assertEquals(sqlBySuffix(base, overlay, ".kt"), view.filesBySuffix(".kt")) }
    }

    private fun indexes(): Pair<Path, Path> {
        val repo = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
        val dir = TestRepos.tmpDir("lookup")
        val base = dir.resolve("base.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), base)
        val overlay = dir.resolve("overlay.db")
        val changed = "package com.example.shop\n\nclass Registry {\n    fun replaced() = 1\n}\n"
        Store.open(overlay).use { db ->
            StoreWriter(db).use { writer ->
                writer.put(IndexedFile(changedPath, "kotlin", "x", changed.length.toLong(), content = changed), Languages.extract(changedPath, changed)!!)
                writer.tombstone(bigPath)
            }
        }
        return base to overlay
    }

    // The queries below are the ones View ran before CL-258.

    private fun sqlBySuffix(base: Path, overlay: Path?, suffix: String): List<String> = withDb(base, overlay) { q ->
        val like = "%" + Like.escape(suffix)
        val rows = q("SELECT path FROM main.files WHERE deleted = 0 AND path LIKE ? ESCAPE '\\'", listOf(like)) { it.getString(1) }
        if (overlay == null) return@withDb rows
        val ov = q("SELECT path, deleted FROM ov.files WHERE path LIKE ? ESCAPE '\\'", listOf(like)) { it.getString(1) to (it.getInt(2) != 0) }
        val gone = ov.filter { it.second }.map { it.first }.toSet()
        LinkedHashSet(rows.filter { it !in gone } + ov.filter { !it.second }.map { it.first }).toList()
    }

    private fun sqlContaining(base: Path, overlay: Path?, literal: String, limit: Int): List<String> = withDb(base, overlay) { q ->
        val sql = "SELECT path FROM main.files WHERE deleted = 0 AND content IS NOT NULL AND instr(content, ?) > 0"
        if (overlay == null) return@withDb q("$sql ORDER BY path LIMIT ?", listOf(literal, limit)) { it.getString(1) }
        val ov = q("SELECT path, deleted, content IS NOT NULL AND instr(content, ?) > 0 FROM ov.files", listOf(literal)) { Triple(it.getString(1), it.getInt(2) != 0, it.getInt(3) != 0) }
        val masked = ov.map { it.first }.toSet()
        val hits = q("$sql ORDER BY path", listOf(literal)) { it.getString(1) }.filter { it !in masked } + ov.filter { !it.second && it.third }.map { it.first }
        hits.sortedWith(PathOrder).take(limit)
    }

    private fun sqlModules(base: Path, overlay: Path?): List<String> = withDb(base, overlay) { q ->
        val rows = q("SELECT DISTINCT module FROM main.files WHERE deleted = 0 AND module IS NOT NULL", emptyList()) { it.getString(1) }
        if (overlay == null) rows else (rows + q("SELECT DISTINCT module FROM ov.files WHERE deleted = 0 AND module IS NOT NULL", emptyList()) { it.getString(1) }).distinct()
    }

    private class Sql(private val db: java.sql.Connection) {
        operator fun <T> invoke(sql: String, params: List<Any>, map: (java.sql.ResultSet) -> T): List<T> = db.prepareStatement(sql).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun <T> withDb(base: Path, overlay: Path?, body: (Sql) -> T): T =
        Store.open(base, readOnly = true).use { db ->
            if (overlay != null) db.createStatement().use { it.execute("ATTACH DATABASE '${overlay.toString().replace("'", "''")}' AS ov") }
            body(Sql(db))
        }
}
