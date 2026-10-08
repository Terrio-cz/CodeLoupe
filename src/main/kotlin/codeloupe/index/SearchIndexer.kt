package codeloupe.index

import java.sql.Connection

/**
 * Keeps the `search` table (SQLite FTS5, one row per declaration, rowid = the declaration's id) in step with the facts.
 * The caller owns the transaction.
 */
class SearchIndexer(db: Connection) : AutoCloseable {
    private val insert = db.prepareStatement("INSERT INTO search(rowid, name, ctx, sig, doc, path) VALUES(?, ?, ?, ?, ?, ?)")
    private val delete = db.prepareStatement("DELETE FROM search WHERE rowid IN (SELECT id FROM decls WHERE file_id = ?)")

    /** Drops the rows of the declarations of file [fileId]; call before its declarations are deleted. */
    fun remove(fileId: Long) {
        delete.setLong(1, fileId)
        delete.executeUpdate()
    }

    fun put(path: String, content: String?, entries: List<SearchEntry>) {
        val lines by lazy(LazyThreadSafetyMode.NONE) { content?.lines().orEmpty() }
        val file = SearchFileWords.of(path)
        for (e in entries) {
            if (e.local || e.kind in SKIPPED_KINDS) continue
            val doc = if (content != null) SearchColumns.docWords(lines, e.startLine, e.declLine) else emptyList()
            // Constructor parameters and fields of a type are looked up by name; indexed, they would be half of the table and most of its noise.
            if (e.kind == "property" && e.container.isNotEmpty() && doc.isEmpty()) continue
            val columns = SearchColumns.of(file, e.kind, e.name, e.container, e.sig, doc)
            insert.setLong(1, e.declId)
            columns.all().forEachIndexed { i, words -> insert.setString(i + 2, words.joinToString(" ")) }
            insert.addBatch()
        }
        insert.executeBatch()
    }

    override fun close() {
        insert.close()
        delete.close()
    }

    companion object {
        /** Kinds the index leaves out: their names are their container's or say nothing. */
        val SKIPPED_KINDS = setOf("constructor", "init", "companion")
    }
}
