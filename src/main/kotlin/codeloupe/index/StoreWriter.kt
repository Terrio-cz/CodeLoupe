package codeloupe.index

import codeloupe.lang.FileFacts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types

/** Replaces the facts of one file at a time. The caller owns the transaction. */
class StoreWriter(private val db: Connection) : AutoCloseable {
    private val fileId = db.prepareStatement("SELECT id FROM files WHERE path = ?")
    private val deleteFile = db.prepareStatement("DELETE FROM files WHERE path = ?")
    private val deleteImports = db.prepareStatement("DELETE FROM imports WHERE file_id = ?")
    private val deleteDecls = db.prepareStatement("DELETE FROM decls WHERE file_id = ?")
    private val deleteRefs = db.prepareStatement("DELETE FROM refs WHERE file_id = ?")
    private val insertFile = db.prepareStatement(
        "INSERT INTO files(path, lang, module, source_set, package, hash, eol, errors, size, mtime, deleted, content) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        Statement.RETURN_GENERATED_KEYS,
    )
    private val insertImport = db.prepareStatement("INSERT INTO imports(file_id, fqn, alias, star) VALUES(?, ?, ?, ?)")
    private val insertDecl = db.prepareStatement(
        """INSERT INTO decls(file_id, kind, name, container, fqn, receiver, params, param_count, returns, modifiers,
           supertypes, start_line, decl_line, end_line, sig, hash, local, parent_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        Statement.RETURN_GENERATED_KEYS,
    )
    private val insertRef = db.prepareStatement("INSERT INTO refs(file_id, name, line, col, kind, recv, decl_id, bind, recv_type, args) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")

    fun remove(path: String) {
        val id = fileId.run { setString(1, path); executeQuery().use { if (it.next()) it.getLong(1) else null } } ?: return
        for (statement in listOf(deleteImports, deleteDecls, deleteRefs)) statement.run { setLong(1, id); executeUpdate() }
        deleteFile.run { setString(1, path); executeUpdate() }
    }

    /** Hides the base copy of a file that a worktree deleted. */
    fun tombstone(path: String) {
        remove(path)
        insertFile.run {
            setString(1, path)
            for (i in 2..7) setNull(i, Types.VARCHAR)
            setInt(8, 0); setLong(9, 0); setLong(10, 0); setInt(11, 1)
            setNull(12, Types.VARCHAR)
            executeUpdate()
        }
    }

    fun put(file: IndexedFile, facts: FileFacts): Long {
        remove(file.path)
        val module = ModulePath.of(file.path)
        val id = insertFile.run {
            setString(1, file.path); setString(2, file.lang); setString(3, module.module); setString(4, module.sourceSet)
            setString(5, facts.packageName); setString(6, file.hash); setString(7, if ("\r\n" in file.content) "crlf" else "lf")
            setInt(8, facts.errors); setLong(9, file.size); setLong(10, file.mtime); setInt(11, 0); setString(12, file.content)
            insertReturningId()
        }
        for (import in facts.imports) insertImport.run {
            setLong(1, id); setString(2, import.fqn); setNullableString(3, import.alias); setInt(4, if (import.star) 1 else 0)
            executeUpdate()
        }
        val declIds = LongArray(facts.decls.size)
        facts.decls.forEachIndexed { i, d ->
            declIds[i] = insertDecl.run {
                setLong(1, id); setString(2, d.kind); setString(3, d.name); setString(4, d.container)
                setString(5, listOf(facts.packageName, d.container, d.name).filter { it.isNotEmpty() }.joinToString("."))
                setNullableString(6, d.receiver)
                setString(7, JsonArray(d.params.map { buildJsonObject { put("name", JsonPrimitive(it.name)); put("type", JsonPrimitive(it.type)) } }).toString())
                setInt(8, d.params.size); setNullableString(9, d.returns)
                setString(10, d.modifiers.joinToString(" ")); setString(11, d.supertypes.joinToString(" "))
                setInt(12, d.start); setInt(13, d.declStart); setInt(14, d.end); setString(15, d.sig); setString(16, d.hash)
                setInt(17, if (d.local) 1 else 0)
                if (d.parent >= 0) setLong(18, declIds[d.parent]) else setNull(18, Types.INTEGER)
                insertReturningId()
            }
        }
        for (r in facts.refs) insertRef.run {
            setLong(1, id); setString(2, r.name); setInt(3, r.line); setInt(4, r.col); setString(5, r.kind); setNullableString(6, r.recv)
            if (r.decl >= 0) setLong(7, declIds[r.decl]) else setNull(7, Types.INTEGER)
            setNullableString(8, r.bind); setNullableString(9, r.recvType)
            if (r.args != null) setInt(10, r.args) else setNull(10, Types.INTEGER)
            addBatch()
        }
        insertRef.executeBatch()
        return id
    }

    override fun close() {
        listOf(fileId, deleteFile, deleteImports, deleteDecls, deleteRefs, insertFile, insertImport, insertDecl, insertRef).forEach { it.close() }
    }

    private fun PreparedStatement.insertReturningId(): Long {
        executeUpdate()
        return generatedKeys.use { it.next(); it.getLong(1) }
    }

    private fun PreparedStatement.setNullableString(index: Int, value: String?) =
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}
