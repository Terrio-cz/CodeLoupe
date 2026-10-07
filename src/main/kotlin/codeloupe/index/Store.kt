package codeloupe.index

import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection

/**
 * SQLite store of per-file facts. One database per base generation (immutable once built) and one per
 * worktree overlay. Only the daemon and its build worker write.
 */
object Store {
    const val SCHEMA_VERSION = 1

    private val SCHEMA = listOf(
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)",
        """CREATE TABLE IF NOT EXISTS files (
  id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, lang TEXT, module TEXT, source_set TEXT, package TEXT,
  hash TEXT, eol TEXT, errors INTEGER, size INTEGER, mtime INTEGER, deleted INTEGER NOT NULL DEFAULT 0, content TEXT)""",
        "CREATE TABLE IF NOT EXISTS imports (file_id INTEGER NOT NULL, fqn TEXT NOT NULL, alias TEXT, star INTEGER NOT NULL)",
        """CREATE TABLE IF NOT EXISTS decls (
  id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, container TEXT NOT NULL,
  fqn TEXT NOT NULL, receiver TEXT, params TEXT, param_count INTEGER, returns TEXT, modifiers TEXT, supertypes TEXT,
  start_line INTEGER, decl_line INTEGER, end_line INTEGER, sig TEXT, hash TEXT, local INTEGER, parent_id INTEGER)""",
        """CREATE TABLE IF NOT EXISTS refs (
  file_id INTEGER NOT NULL, name TEXT NOT NULL, line INTEGER, col INTEGER, kind TEXT, recv TEXT, decl_id INTEGER)""",
        "CREATE INDEX IF NOT EXISTS decls_name ON decls(name)",
        "CREATE INDEX IF NOT EXISTS decls_file ON decls(file_id)",
        "CREATE INDEX IF NOT EXISTS refs_name ON refs(name)",
        "CREATE INDEX IF NOT EXISTS refs_file ON refs(file_id)",
        "CREATE INDEX IF NOT EXISTS imports_file ON imports(file_id)",
        "CREATE INDEX IF NOT EXISTS imports_fqn ON imports(fqn)",
    )

    fun open(file: Path, readOnly: Boolean = false): Connection {
        val config = SQLiteConfig().apply {
            setReadOnly(readOnly)
            setBusyTimeout(5000)
            if (!readOnly) {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            }
        }
        val connection = config.createConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        if (!readOnly) connection.createStatement().use { s -> SCHEMA.forEach(s::execute) }
        return connection
    }

    fun getMeta(db: Connection, key: String): String? =
        db.prepareStatement("SELECT value FROM meta WHERE key = ?").use { s ->
            s.setString(1, key)
            s.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    fun setMeta(db: Connection, key: String, value: Any) {
        db.prepareStatement("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use { s ->
            s.setString(1, key)
            s.setString(2, value.toString())
            s.executeUpdate()
        }
    }
}
