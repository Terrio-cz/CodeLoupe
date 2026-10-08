package codeloupe.taskcode

import codeloupe.tracker.mirror.batch
import codeloupe.tracker.mirror.exec
import codeloupe.tracker.mirror.query
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * `<home>/repos/<id>/tasks.db`: the commits of the default branch that mention a task, the files each one changed
 * and, once asked for, the declarations. One connection under a lock, like the tracker mirror. A file of another
 * schema version or task pattern is dropped and scanned again.
 */
class TaskCodeStore(file: Path, pattern: String) : AutoCloseable {
    private val db: Connection

    init {
        Files.createDirectories(file.parent)
        val config = SQLiteConfig().apply {
            setBusyTimeout(5000)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        }
        var connection = config.createConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        val version = connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getInt(1) } }
        val samePattern = version == SCHEMA_VERSION && connection.query("SELECT value FROM meta WHERE key = 'pattern'") { it.getString(1) }.firstOrNull() == pattern
        if (version != 0 && !samePattern) {
            connection.close()
            listOf("", "-wal", "-shm").forEach { Files.deleteIfExists(Path.of("$file$it")) }
            connection = config.createConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        }
        try {
            connection.createStatement().use { s ->
                SCHEMA.forEach(s::execute)
                s.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
            connection.exec("INSERT INTO meta(key, value) VALUES('pattern', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", pattern)
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        db = connection
    }

    fun <T> read(block: (Connection) -> T): T = synchronized(db) { block(db) }

    fun <T> write(block: (Connection) -> T): T = synchronized(db) {
        db.autoCommit = false
        try {
            block(db).also { db.commit() }
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    /** The commit the last scan of [ref] reached, or null before the first one. */
    fun scannedTip(ref: String): String? = read { db -> db.query("SELECT tip FROM scans WHERE ref = ?", ref) { it.getString(1) }.firstOrNull() }

    fun markScanned(db: Connection, ref: String, tip: String, at: Long) =
        db.exec("INSERT INTO scans(ref, tip, scanned_at) VALUES(?, ?, ?) ON CONFLICT(ref) DO UPDATE SET tip = excluded.tip, scanned_at = excluded.scanned_at", ref, tip, at)

    /** Forgets every commit: history was rewritten under the last scan. */
    fun clear(db: Connection) = listOf("decl_done", "commit_decls", "commit_files", "commit_tasks", "commits", "scans").forEach { db.exec("DELETE FROM $it") }

    fun known(db: Connection, sha: String): Boolean = db.query("SELECT 1 FROM commits WHERE sha = ?", sha) { true }.isNotEmpty()

    fun putCommit(db: Connection, commit: TaskCommit, tasks: Collection<String>, files: List<CommitFile>) {
        db.exec(
            "INSERT OR REPLACE INTO commits(sha, time, subject, parent, merge, included) VALUES(?, ?, ?, ?, ?, ?)",
            commit.sha, commit.time, commit.subject, commit.parent, if (commit.merge) 1 else 0, if (commit.included) 1 else 0,
        )
        db.exec("DELETE FROM commit_tasks WHERE sha = ?", commit.sha)
        db.exec("DELETE FROM commit_files WHERE sha = ?", commit.sha)
        db.batch("INSERT INTO commit_tasks(sha, task) VALUES(?, ?)", tasks.toList()) { listOf(commit.sha, it) }
        db.batch("INSERT INTO commit_files(sha, path, status, old_blob, new_blob) VALUES(?, ?, ?, ?, ?)", files) {
            listOf(commit.sha, it.path, it.status.toString(), it.oldBlob, it.newBlob)
        }
    }

    /** The included commits of [task], oldest first. */
    fun commitsOf(task: String): List<TaskCommit> = read { db ->
        db.query("$COMMIT FROM commits c JOIN commit_tasks t ON t.sha = c.sha WHERE t.task = ? AND c.included = 1 ORDER BY c.time, c.sha", task.uppercase(), row = ::commit)
    }

    /** Every commit mentioning [task], included or not (a task that was only ever merged in shows up here). */
    fun allCommitsOf(task: String): List<TaskCommit> = read { db ->
        db.query("$COMMIT FROM commits c JOIN commit_tasks t ON t.sha = c.sha WHERE t.task = ? ORDER BY c.time, c.sha", task.uppercase(), row = ::commit)
    }

    /** Tasks the commit mentions. */
    fun tasksOf(db: Connection, sha: String): List<String> = db.query("SELECT task FROM commit_tasks WHERE sha = ? ORDER BY task", sha) { it.getString(1) }

    fun filesOf(sha: String): List<CommitFile> = read { db ->
        db.query("SELECT path, status, old_blob, new_blob FROM commit_files WHERE sha = ? ORDER BY path", sha) {
            CommitFile(it.getString(1), it.getString(2)[0], it.getString(3), it.getString(4))
        }
    }

    /** Included commits that touched [path], newest first, each with the tasks it mentions. */
    fun commitsTouching(path: String): List<Pair<TaskCommit, List<String>>> = read { db ->
        db.query("$COMMIT FROM commits c JOIN commit_files f ON f.sha = c.sha WHERE f.path = ? AND c.included = 1 ORDER BY c.time DESC, c.sha", path, row = ::commit)
            .map { it to tasksOf(db, it.sha) }
    }

    /** The paths of [sha] whose declaration changes are stored. */
    fun declsDone(sha: String): Set<String> = read { db -> db.query("SELECT path FROM decl_done WHERE sha = ?", sha) { it.getString(1) }.toSet() }

    /** Stores the declaration changes of [sha] in [paths] (possibly none for a path). */
    fun putDecls(sha: String, paths: Collection<String>, decls: List<CommitDecl>) = write { db ->
        db.batch("DELETE FROM commit_decls WHERE sha = ? AND path = ?", paths.toList()) { listOf(sha, it) }
        db.batch("INSERT INTO commit_decls(sha, path, mark, kind, container, name, sig, start_line, end_line) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)", decls) {
            listOf(sha, it.path, it.mark.toString(), it.kind, it.container, it.name, it.sig, it.startLine, it.endLine)
        }
        db.batch("INSERT OR IGNORE INTO decl_done(sha, path) VALUES(?, ?)", paths.toList()) { listOf(sha, it) }
    }

    fun declsOf(sha: String, path: String? = null): List<CommitDecl> = read { db ->
        val where = if (path == null) "sha = ?" else "sha = ? AND path = ?"
        db.query("SELECT path, mark, kind, container, name, sig, start_line, end_line FROM commit_decls WHERE $where ORDER BY path, start_line", *listOfNotNull(sha, path).toTypedArray()) {
            CommitDecl(it.getString(1), it.getString(2)[0], it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getInt(7), it.getInt(8))
        }
    }

    fun counts(): Pair<Int, Int> = read { db ->
        db.query("SELECT count(*), (SELECT count(DISTINCT task) FROM commit_tasks) FROM commits") { it.getInt(1) to it.getInt(2) }.first()
    }

    override fun close() = synchronized(db) { db.close() }

    private fun commit(rs: java.sql.ResultSet) = TaskCommit(
        sha = rs.getString(1), time = rs.getLong(2), subject = rs.getString(3), parent = rs.getString(4), merge = rs.getInt(5) == 1, included = rs.getInt(6) == 1,
    )

    companion object {
        const val SCHEMA_VERSION = 1
        private const val COMMIT = "SELECT c.sha, c.time, c.subject, c.parent, c.merge, c.included"

        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)",
            "CREATE TABLE IF NOT EXISTS scans (ref TEXT PRIMARY KEY, tip TEXT NOT NULL, scanned_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS commits (sha TEXT PRIMARY KEY, time INTEGER NOT NULL, subject TEXT NOT NULL, parent TEXT, merge INTEGER NOT NULL, included INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS commit_tasks (sha TEXT NOT NULL, task TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS commit_tasks_task ON commit_tasks(task)",
            "CREATE INDEX IF NOT EXISTS commit_tasks_sha ON commit_tasks(sha)",
            "CREATE TABLE IF NOT EXISTS commit_files (sha TEXT NOT NULL, path TEXT NOT NULL, status TEXT NOT NULL, old_blob TEXT, new_blob TEXT)",
            "CREATE INDEX IF NOT EXISTS commit_files_sha ON commit_files(sha)",
            "CREATE INDEX IF NOT EXISTS commit_files_path ON commit_files(path)",
            "CREATE TABLE IF NOT EXISTS commit_decls (sha TEXT NOT NULL, path TEXT NOT NULL, mark TEXT NOT NULL, kind TEXT NOT NULL, container TEXT NOT NULL, name TEXT NOT NULL, sig TEXT NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS commit_decls_sha ON commit_decls(sha, path)",
            "CREATE TABLE IF NOT EXISTS decl_done (sha TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(sha, path))",
        )
    }
}
