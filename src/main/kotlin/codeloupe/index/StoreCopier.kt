package codeloupe.index

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types

/**
 * Copies the facts of single files from one store into another, row for row, without parsing: a worktree file
 * that is unchanged since an earlier base still has that base's facts. The caller owns the target transaction.
 */
class StoreCopier(source: Connection, private val target: Connection) : AutoCloseable {
    private val file = source.prepareStatement("SELECT id, lang, module, source_set, package, hash, eol, errors, content FROM files WHERE path = ? AND deleted = 0")
    private val fileWithHash = source.prepareStatement(
        "SELECT id, lang, module, source_set, package, hash, eol, errors, content FROM files WHERE path = ? AND hash = ? AND deleted = 0",
    )
    private val imports = source.prepareStatement("SELECT fqn, alias, star FROM imports WHERE file_id = ?")
    private val decls = source.prepareStatement("SELECT id, $DECL_COLUMNS, parent_id FROM decls WHERE file_id = ? ORDER BY id")
    private val refs = source.prepareStatement("SELECT $REF_COLUMNS, decl_id FROM refs WHERE file_id = ?")
    private val insertFile = target.prepareStatement(
        "INSERT INTO files(path, lang, module, source_set, package, hash, eol, errors, size, mtime, deleted, content) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
        Statement.RETURN_GENERATED_KEYS,
    )
    private val insertImport = target.prepareStatement("INSERT INTO imports(file_id, fqn, alias, star) VALUES(?, ?, ?, ?)")
    private val insertDecl = target.prepareStatement(
        "INSERT INTO decls(file_id, $DECL_COLUMNS, parent_id) VALUES(?, ${List(DECL_COLUMN_COUNT) { "?" }.joinToString()}, ?)",
        Statement.RETURN_GENERATED_KEYS,
    )
    private val insertRef = target.prepareStatement(
        "INSERT INTO refs(file_id, $REF_COLUMNS, decl_id) VALUES(?, ${List(REF_COLUMN_COUNT) { "?" }.joinToString()}, ?)",
    )

    /** Copies [path] (already removed from the target) with a new stamp; false when the source does not have it. */
    fun copy(path: String, size: Long, mtime: Long): Boolean = copy(file, path, size, mtime)

    /** As [copy], but only when the source holds [path] with the content of hash [hash]: the same text has the same facts. */
    fun copyIfSame(path: String, hash: String, size: Long, mtime: Long): Boolean = copy(fileWithHash.also { it.setString(2, hash) }, path, size, mtime)

    private fun copy(select: PreparedStatement, path: String, size: Long, mtime: Long): Boolean {
        val (oldId, newId) = select.run {
            setString(1, path)
            executeQuery().use { rs ->
                if (!rs.next()) return false
                rs.getLong(1) to insertFile.run {
                    setString(1, path)
                    for (i in 2..8) setObject(i, rs.getObject(i))
                    setLong(9, size); setLong(10, mtime); setString(11, rs.getString(9))
                    insertReturningId()
                }
            }
        }
        imports.rows(oldId) { rs ->
            insertImport.setLong(1, newId)
            for (i in 1..3) insertImport.setObject(i + 1, rs.getObject(i))
            insertImport.executeUpdate()
        }
        // Parents precede their children (ids grow in declaration order), so every parent is mapped when needed.
        val declIds = HashMap<Long, Long>()
        decls.rows(oldId) { rs ->
            insertDecl.setLong(1, newId)
            for (i in 1..DECL_COLUMN_COUNT) insertDecl.setObject(i + 1, rs.getObject(i + 1))
            val parent = rs.getLong(DECL_COLUMN_COUNT + 2).takeUnless { rs.wasNull() }
            if (parent == null) insertDecl.setNull(DECL_COLUMN_COUNT + 2, Types.INTEGER) else insertDecl.setLong(DECL_COLUMN_COUNT + 2, declIds.getValue(parent))
            declIds[rs.getLong(1)] = insertDecl.insertReturningId()
        }
        refs.rows(oldId) { rs ->
            insertRef.setLong(1, newId)
            for (i in 1..REF_COLUMN_COUNT) insertRef.setObject(i + 1, rs.getObject(i))
            val decl = rs.getLong(REF_COLUMN_COUNT + 1).takeUnless { rs.wasNull() }
            val declIndex = REF_COLUMN_COUNT + 2
            if (decl == null) insertRef.setNull(declIndex, Types.INTEGER) else insertRef.setLong(declIndex, declIds.getValue(decl))
            insertRef.addBatch()
        }
        insertRef.executeBatch()
        return true
    }

    override fun close() {
        listOf(file, fileWithHash, imports, decls, refs, insertFile, insertImport, insertDecl, insertRef).forEach { it.close() }
    }

    private inline fun PreparedStatement.rows(fileId: Long, each: (java.sql.ResultSet) -> Unit) {
        setLong(1, fileId)
        executeQuery().use { rs -> while (rs.next()) each(rs) }
    }

    private fun PreparedStatement.insertReturningId(): Long {
        executeUpdate()
        return generatedKeys.use { it.next(); it.getLong(1) }
    }

    private companion object {
        const val DECL_COLUMNS = "kind, name, container, fqn, receiver, params, param_count, returns, modifiers, supertypes, " +
            "start_line, decl_line, end_line, sig, hash, local"
        val DECL_COLUMN_COUNT = DECL_COLUMNS.split(',').size

        // Every refs column but the ids; StoreCopierTest fails when the schema gains one this list lacks.
        const val REF_COLUMNS = "name, line, col, kind, recv, bind, recv_type, args"
        val REF_COLUMN_COUNT = REF_COLUMNS.split(',').size
    }
}
