package codeloupe.tracker.mirror

import codeloupe.JsonFormat
import codeloupe.tracker.Criterion
import codeloupe.tracker.FieldChange
import codeloupe.tracker.IssueAttachment
import codeloupe.tracker.IssueComment
import codeloupe.tracker.TrackerIssue
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

/**
 * SQLite mirror of one tracker instance: issues with their fields, links, criteria, comments, attachment metadata,
 * field history and earlier revisions (for deltas), plus FTS5 over summary, description and comments. One
 * connection; every access holds its lock, so reads see whole issues. Ids are stored as the tracker spells them.
 */
class MirrorStore(file: Path) : AutoCloseable {
    private val db: Connection

    init {
        Files.createDirectories(file.parent)
        val config = SQLiteConfig().apply {
            setBusyTimeout(5000)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        }
        db = open(file, config)
    }

    /** The mirror is a cache: a file of another schema version is dropped and loaded again from the tracker. */
    private fun open(file: Path, config: SQLiteConfig): Connection {
        val existing = config.createConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        val (version, tables) = closingOnFailure(existing) {
            it.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getInt(1) } } to
                it.query("SELECT count(*) FROM sqlite_master") { r -> r.getInt(1) }.first()
        }
        val connection = if (version == SCHEMA_VERSION || tables == 0) existing else {
            existing.close()
            listOf("", "-wal", "-shm").forEach { Files.deleteIfExists(Path.of("$file$it")) }
            config.createConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        }
        closingOnFailure(connection) {
            it.createStatement().use { s ->
                SCHEMA.forEach(s::execute)
                s.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
        }
        return connection
    }

    /** On Windows an open handle keeps the file locked: a connection that fails to set up is closed at once. */
    private fun <T> closingOnFailure(connection: Connection, block: (Connection) -> T): T = try {
        block(connection)
    } catch (e: Exception) {
        connection.close()
        throw e
    }

    fun <T> read(block: (Connection) -> T): T = synchronized(db) { block(db) }

    private fun <T> write(block: (Connection) -> T): T = synchronized(db) {
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

    /** Stores [issues]; an issue whose `updated` changed keeps its previous version as a revision. Returns how many changed. */
    fun upsert(issues: List<TrackerIssue>, now: Long): Int = write { db ->
        issues.count { upsertOne(db, it, now) }
    }

    private fun upsertOne(db: Connection, issue: TrackerIssue, now: Long): Boolean {
        val previous = db.query("SELECT updated, json FROM issues WHERE id = ?", issue.id) { it.getLong(1) to it.getString(2) }.firstOrNull()
        // Never step back: a slower fetch must not replace a newer version.
        if (previous != null && previous.first >= issue.updated) return false
        if (previous != null) {
            db.exec("INSERT OR IGNORE INTO revisions(issue, updated, stored_at, json) VALUES(?, ?, ?, ?)", issue.id, previous.first, now, previous.second)
            db.exec(
                "DELETE FROM revisions WHERE issue = ? AND (stored_at < ? OR updated NOT IN (SELECT updated FROM revisions WHERE issue = ? ORDER BY updated DESC LIMIT ?))",
                issue.id, now - REVISION_MS, issue.id, REVISIONS,
            )
        }
        deleteRows(db, issue.id)
        val core = issue.copy(comments = emptyList(), attachments = emptyList())
        db.exec(
            "INSERT INTO issues(id, project, num, summary, state, type, priority, priority_rank, assignee, resolved, parent, created, updated, json) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            issue.id, issue.project, number(issue.id), issue.summary, issue.state, issue.type, issue.priority, issue.priorityRank, issue.assignee,
            issue.resolved, issue.parent, issue.created, issue.updated, JsonFormat.json.encodeToString(TrackerIssue.serializer(), core),
        )
        db.batch("INSERT INTO fields(issue, name, value) VALUES(?, ?, ?)", issue.fields) { listOf(issue.id, it.name, it.value) }
        db.batch("INSERT INTO links(issue, kind, verb, other) VALUES(?, ?, ?, ?)", issue.links) { listOf(issue.id, it.kind.name, it.verb, it.other) }
        db.batch("INSERT INTO criteria(issue, pos, done, text) VALUES(?, ?, ?, ?)", Criterion.parse(issue.description).withIndex().toList()) {
            listOf(issue.id, it.index, if (it.value.done) 1 else 0, it.value.text)
        }
        db.batch("INSERT INTO comments(id, issue, author, created, updated, text) VALUES(?, ?, ?, ?, ?, ?)", issue.comments) {
            listOf(it.id, issue.id, it.author, it.created, it.updated, it.text)
        }
        db.batch("INSERT INTO attachments(id, issue, name, size, mime, created, author) VALUES(?, ?, ?, ?, ?, ?, ?)", issue.attachments) {
            listOf(it.id, issue.id, it.name, it.size, it.mime, it.created, it.author)
        }
        db.exec(
            "INSERT INTO issues_fts(rowid, summary, description, comments) SELECT rowid, ?, ?, ? FROM issues WHERE id = ?",
            issue.summary, issue.description, issue.comments.joinToString("\n") { it.text }, issue.id,
        )
        return true
    }

    /** Removes issues with everything that hangs on them; ids in any case. */
    fun delete(ids: Collection<String>) = write { db ->
        ids.map { id -> db.query("SELECT id FROM issues WHERE id = ?", id) { it.getString(1) }.firstOrNull() ?: id }.forEach {
            deleteRows(db, it)
            db.exec("DELETE FROM revisions WHERE issue = ?", it)
            db.exec("DELETE FROM field_changes WHERE issue = ?", it)
        }
    }

    private fun deleteRows(db: Connection, id: String) {
        db.exec("DELETE FROM issues_fts WHERE rowid IN (SELECT rowid FROM issues WHERE id = ?)", id)
        for ((table, column) in listOf("issues" to "id", "fields" to "issue", "links" to "issue", "criteria" to "issue", "comments" to "issue", "attachments" to "issue")) {
            db.exec("DELETE FROM $table WHERE $column = ?", id)
        }
    }

    fun addChanges(project: String, changes: List<FieldChange>) = write { db ->
        db.batch("INSERT OR IGNORE INTO field_changes(id, project, issue, at, field, removed, added, author) VALUES(?, ?, ?, ?, ?, ?, ?, ?)", changes) {
            listOf(it.id, project, it.issue, it.at, it.field, it.removed, it.added, it.author)
        }
    }

    fun lastChange(project: String): Long? = read { db -> db.query("SELECT max(at) FROM field_changes WHERE project = ?", project) { it.long(1) }.firstOrNull() }

    fun changes(issue: String): List<FieldChange> = read { db ->
        db.query("SELECT id, issue, at, field, removed, added, author FROM field_changes WHERE issue = ? ORDER BY at", issue) {
            FieldChange(it.getString(1), it.getString(2), it.getLong(3), it.getString(4), it.getString(5).orEmpty(), it.getString(6).orEmpty(), it.getString(7))
        }
    }

    /** The issue without comments and attachments; null when not mirrored. Any case of [id] finds it. */
    fun issue(id: String): TrackerIssue? = read { db -> db.query("SELECT json FROM issues WHERE id = ?", id) { decode(it.getString(1)) }.firstOrNull() }

    fun comments(id: String): List<IssueComment> = read { db ->
        db.query("SELECT id, author, created, updated, text FROM comments WHERE issue = ? ORDER BY created", id) {
            IssueComment(it.getString(1), it.getString(2), it.getLong(3), it.long(4), it.getString(5))
        }
    }

    /** How many comments [id] has and the newest one without its text: what a brief shows. */
    fun lastComment(id: String): Pair<Int, IssueComment?> = read { db ->
        val count = db.query("SELECT count(*) FROM comments WHERE issue = ?", id) { it.getInt(1) }.first()
        count to db.query("SELECT id, author, created, updated FROM comments WHERE issue = ? ORDER BY created DESC LIMIT 1", id) {
            IssueComment(it.getString(1), it.getString(2), it.getLong(3), it.long(4), "")
        }.firstOrNull()
    }

    fun attachments(id: String): List<IssueAttachment> = read { db ->
        db.query("SELECT id, name, size, mime, created, author FROM attachments WHERE issue = ? ORDER BY created", id) {
            IssueAttachment(it.getString(1), it.getString(2), it.getLong(3), it.getString(4), it.getLong(5), it.getString(6))
        }
    }

    /** The version of [id] that was current at [updated]: the newest stored revision not newer than it. */
    fun revision(id: String, updated: Long): TrackerIssue? = read { db ->
        db.query("SELECT json FROM revisions WHERE issue = ? AND updated <= ? ORDER BY updated DESC LIMIT 1", id, updated) { decode(it.getString(1)) }.firstOrNull()
    }

    fun updated(project: String): Map<String, Long> = read { db -> db.query("SELECT id, updated FROM issues WHERE project = ?", project) { it.getString(1) to it.getLong(2) }.toMap() }

    fun state(project: String): ProjectState = read { db ->
        val count = db.query("SELECT count(*) FROM issues WHERE project = ?", project) { it.getInt(1) }.first()
        db.query("SELECT synced_at, checked_at, watermark, error FROM projects WHERE short = ?", project) {
            ProjectState(project, it.long(1), it.long(2), it.long(3), count, it.getString(4))
        }.firstOrNull() ?: ProjectState(project, null, null, null, count, null)
    }

    /** Records a finished sync; [watermark] is the newest `updated` the tracker listed, never moved back. */
    fun markSynced(project: String, at: Long, watermark: Long?, checked: Boolean) = write { db ->
        db.exec(
            "INSERT INTO projects(short, synced_at, checked_at, watermark, error) VALUES(?, ?, ?, ?, NULL) ON CONFLICT(short) DO UPDATE SET " +
                "synced_at = excluded.synced_at, checked_at = coalesce(excluded.checked_at, projects.checked_at), " +
                "watermark = max(coalesce(excluded.watermark, 0), coalesce(projects.watermark, 0)), error = NULL",
            project, at, if (checked) at else null, watermark,
        )
    }

    fun markFailed(project: String, error: String) = write { db ->
        db.exec("INSERT INTO projects(short, error) VALUES(?, ?) ON CONFLICT(short) DO UPDATE SET error = excluded.error", project, error)
    }

    override fun close() = synchronized(db) { db.close() }

    private fun decode(json: String) = JsonFormat.json.decodeFromString(TrackerIssue.serializer(), json)

    private fun ResultSet.long(column: Int): Long? = getLong(column).takeUnless { wasNull() }

    companion object {
        const val SCHEMA_VERSION = 1

        /** Earlier versions kept per issue, and for how long: enough for "what changed since my last read". */
        const val REVISIONS = 5
        const val REVISION_MS = 30L * 24 * 3600_000

        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS projects (short TEXT PRIMARY KEY, synced_at INTEGER, checked_at INTEGER, watermark INTEGER, error TEXT)",
            """CREATE TABLE IF NOT EXISTS issues (id TEXT PRIMARY KEY COLLATE NOCASE, project TEXT NOT NULL, num INTEGER NOT NULL, summary TEXT NOT NULL,
  state TEXT, type TEXT, priority TEXT, priority_rank INTEGER, assignee TEXT, resolved INTEGER, parent TEXT, created INTEGER, updated INTEGER NOT NULL,
  json TEXT NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS issues_project ON issues(project, num)",
            "CREATE INDEX IF NOT EXISTS issues_parent ON issues(parent)",
            "CREATE TABLE IF NOT EXISTS fields (issue TEXT NOT NULL, name TEXT NOT NULL COLLATE NOCASE, value TEXT NOT NULL COLLATE NOCASE)",
            "CREATE INDEX IF NOT EXISTS fields_issue ON fields(issue)",
            "CREATE TABLE IF NOT EXISTS links (issue TEXT NOT NULL, kind TEXT NOT NULL, verb TEXT NOT NULL, other TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS links_issue ON links(issue)",
            "CREATE TABLE IF NOT EXISTS criteria (issue TEXT NOT NULL, pos INTEGER NOT NULL, done INTEGER NOT NULL, text TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS criteria_issue ON criteria(issue)",
            "CREATE TABLE IF NOT EXISTS comments (id TEXT PRIMARY KEY, issue TEXT NOT NULL, author TEXT, created INTEGER, updated INTEGER, text TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS comments_issue ON comments(issue)",
            "CREATE TABLE IF NOT EXISTS attachments (id TEXT PRIMARY KEY, issue TEXT NOT NULL, name TEXT, size INTEGER, mime TEXT, created INTEGER, author TEXT)",
            "CREATE INDEX IF NOT EXISTS attachments_issue ON attachments(issue)",
            """CREATE TABLE IF NOT EXISTS field_changes (id TEXT PRIMARY KEY, project TEXT NOT NULL, issue TEXT NOT NULL, at INTEGER NOT NULL, field TEXT NOT NULL,
  removed TEXT, added TEXT, author TEXT)""",
            "CREATE INDEX IF NOT EXISTS field_changes_issue ON field_changes(issue, at)",
            "CREATE INDEX IF NOT EXISTS field_changes_project ON field_changes(project, at)",
            "CREATE TABLE IF NOT EXISTS revisions (issue TEXT NOT NULL, updated INTEGER NOT NULL, stored_at INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(issue, updated))",
            // Contentless: the text lives in issues/comments once; the index row shares the issue's rowid.
            "CREATE VIRTUAL TABLE IF NOT EXISTS issues_fts USING fts5(summary, description, comments, content = '', contentless_delete = 1, " +
                "tokenize = 'unicode61 remove_diacritics 2')",
        )

        fun number(id: String): Long = id.substringAfterLast('-').toLongOrNull() ?: 0
    }
}
