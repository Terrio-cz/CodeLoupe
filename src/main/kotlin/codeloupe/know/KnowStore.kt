package codeloupe.know

import codeloupe.JsonFormat
import codeloupe.platform.Sqlite
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

/** `<home>/know.db`: the facts and their full-text index. One connection, calls serialized. */
class KnowStore(file: Path) : AutoCloseable {
    private val db: Connection = Sqlite.open(file, SCHEMA)

    class Upsert(val fact: Fact, val added: Boolean)

    /**
     * Stores [fact] (its id is ignored): as the older fact of the same scope when the stems are near-identical ([Dedupe]), else as a new one.
     * [words] are the stems of its text. A fact written as superseded is stored as it is.
     */
    @Synchronized
    fun upsert(fact: Fact, words: List<String>): Upsert {
        val twin = if (fact.status == FactStatus.SUPERSEDED) null else Dedupe.nearest(words.toSet(), candidates(fact.scope, fact.target))
        if (twin == null) return Upsert(insert(fact, words), added = true)
        val merged = FactMerge.of(get(twin.id)!!, fact)
        write(merged, words)
        return Upsert(merged, added = false)
    }

    @Synchronized
    fun get(id: String): Fact? = parseId(id)?.let(::get)

    /**
     * The best [limit] facts for [match] (see [FtsMatch]) that [filter] lets through, best first; without a match the most recently
     * updated ones, which only makes sense with an anchor in the filter.
     */
    @Synchronized
    fun search(match: String?, filter: KnowFilter, limit: Int): List<Fact> {
        val where = StringBuilder(if (match == null) "1 = 1" else "facts_fts MATCH ?")
        val params = ArrayList<Any>()
        match?.let { params += it }
        if (!filter.includeSuperseded) where.append(" AND f.status != 'superseded'")
        filter.anchor?.let {
            where.append(" AND f.anchors LIKE ? ESCAPE '\\'")
            params += AnchorMatch.prefilter(it)
        }
        if (filter.scopes.isNotEmpty()) {
            where.append(" AND (").append(filter.scopes.joinToString(" OR ") { if (it.target.isEmpty()) "f.scope = ?" else "(f.scope = ? AND f.target = ?)" }).append(')')
            filter.scopes.forEach {
                params += it.kind.word
                if (it.target.isNotEmpty()) params += it.target
            }
        }
        if (filter.kinds.isNotEmpty()) {
            where.append(" AND f.kind IN (").append(filter.kinds.joinToString(",") { "?" }).append(')')
            filter.kinds.forEach { params += it.word }
        }
        params += limit
        val sql = if (match == null) {
            "SELECT f.* FROM facts f WHERE $where ORDER BY f.updated_at DESC, f.id DESC LIMIT ?"
        } else {
            "SELECT f.* FROM facts_fts JOIN facts f ON f.id = facts_fts.rowid WHERE $where ORDER BY bm25(facts_fts, 1.0, 0.3), f.id LIMIT ?"
        }
        return query(sql, params) { fact(it) }
    }

    /** Facts that are tied to code and still stand: what a staleness check has to read. */
    @Synchronized
    fun anchored(): List<Fact> = query("SELECT * FROM facts WHERE anchors != '[]' AND status != 'superseded' ORDER BY id", emptyList()) { fact(it) }

    /** Facts in the order they were written, [limit] of them from the id after [after]. */
    @Synchronized
    fun all(after: Long = 0, limit: Int = 1000): List<Fact> = query("SELECT * FROM facts WHERE id > ? ORDER BY id LIMIT ?", listOf(after, limit)) { fact(it) }

    @Synchronized
    fun count(): Int = query("SELECT count(*) FROM facts", emptyList()) { it.getInt(1) }.single()

    /** Stores [fact] as it is, text and index included. */
    @Synchronized
    fun update(fact: Fact) {
        val words = query("SELECT words FROM facts WHERE id = ?", listOf(parseId(fact.id)!!)) { it.getString(1) }.firstOrNull() ?: return
        write(fact, words.split(' ').filter { it.isNotEmpty() })
    }

    /** Marks [ids] as replaced by [by]; answers how many were replaced now. */
    @Synchronized
    fun supersede(ids: List<String>, by: String, at: String): Int =
        ids.mapNotNull { get(it) }.filter { it.id != by && it.status != FactStatus.SUPERSEDED }
            .onEach { update(it.copy(status = FactStatus.SUPERSEDED, staleReason = "replaced by $by", updatedAt = at)) }.size

    @Synchronized
    override fun close() = db.close()

    private fun candidates(scope: FactScope, target: String): List<Dedupe.Candidate> =
        query("SELECT id, words FROM facts WHERE scope = ? AND target = ? AND status != 'superseded'", listOf(scope.word, target)) {
            Dedupe.Candidate(it.getLong(1), it.getString(2).split(' ').filter(String::isNotEmpty).toSet())
        }

    private fun get(rowid: Long): Fact? = query("SELECT * FROM facts WHERE id = ?", listOf(rowid)) { fact(it) }.firstOrNull()

    private fun insert(fact: Fact, words: List<String>): Fact {
        val rowid = db.prepareStatement(
            "INSERT INTO facts(scope, target, kind, text, words, created_at, updated_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf("id"),
        ).use { s ->
            s.setString(1, fact.scope.word)
            s.setString(2, fact.target)
            s.setString(3, fact.kind.word)
            s.setString(4, fact.text)
            s.setString(5, words.joinToString(" "))
            s.setString(6, fact.createdAt)
            s.setString(7, fact.updatedAt)
            s.setString(8, fact.status.word)
            s.executeUpdate()
            s.generatedKeys.use { it.next(); it.getLong(1) }
        }
        val stored = fact.copy(id = "k$rowid")
        write(stored, words)
        return stored
    }

    private fun write(fact: Fact, words: List<String>) {
        val rowid = parseId(fact.id)!!
        db.prepareStatement(
            "UPDATE facts SET scope = ?, target = ?, kind = ?, text = ?, words = ?, task = ?, run = ?, verified_at = ?, supersedes = ?, related = ?, anchors = ?, " +
                "updated_at = ?, status = ?, stale_reason = ? WHERE id = ?",
        ).use { s ->
            s.setString(1, fact.scope.word)
            s.setString(2, fact.target)
            s.setString(3, fact.kind.word)
            s.setString(4, fact.text)
            s.setString(5, words.joinToString(" "))
            s.setString(6, fact.source.task)
            s.setString(7, fact.source.run)
            s.setString(8, fact.verifiedAtCommit)
            s.setString(9, JsonFormat.json.encodeToString(STRINGS, fact.supersedes))
            s.setString(10, JsonFormat.json.encodeToString(STRINGS, fact.related))
            s.setString(11, JsonFormat.json.encodeToString(ANCHORS, fact.anchors))
            s.setString(12, fact.updatedAt)
            s.setString(13, fact.status.word)
            s.setString(14, fact.staleReason)
            s.setLong(15, rowid)
            s.executeUpdate()
        }
        db.prepareStatement("DELETE FROM facts_fts WHERE rowid = ?").use { it.setLong(1, rowid); it.executeUpdate() }
        db.prepareStatement("INSERT INTO facts_fts(rowid, words, meta) VALUES (?, ?, ?)").use { s ->
            s.setLong(1, rowid)
            s.setString(2, words.joinToString(" "))
            s.setString(3, metaWords(fact).joinToString(" "))
            s.executeUpdate()
        }
    }

    // What a question may name besides the text: the scope and its target, the kind, the task.
    private fun metaWords(fact: Fact): List<String> = FactText.words("${fact.scope.word} ${fact.target} ${fact.kind.word} ${fact.source.task.orEmpty()}")

    private fun fact(r: ResultSet) = Fact(
        id = "k${r.getLong("id")}", scope = FactScope.entries.first { it.word == r.getString("scope") }, target = r.getString("target"),
        kind = FactKind.parse(r.getString("kind")), text = r.getString("text"), source = FactSource(r.getString("task"), r.getString("run")),
        verifiedAtCommit = r.getString("verified_at"), supersedes = JsonFormat.json.decodeFromString(STRINGS, r.getString("supersedes")),
        related = JsonFormat.json.decodeFromString(STRINGS, r.getString("related")), anchors = JsonFormat.json.decodeFromString(ANCHORS, r.getString("anchors")),
        createdAt = r.getString("created_at"), updatedAt = r.getString("updated_at"), status = FactStatus.parse(r.getString("status")), staleReason = r.getString("stale_reason"),
    )

    private fun <T> query(sql: String, params: List<Any>, map: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { s ->
        params.forEachIndexed { i, p ->
            when (p) {
                is Long -> s.setLong(i + 1, p)
                is Int -> s.setInt(i + 1, p)
                else -> s.setString(i + 1, p.toString())
            }
        }
        s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
    }

    companion object {
        private val STRINGS = ListSerializer(String.serializer())
        private val ANCHORS = ListSerializer(Anchor.serializer())

        /** `k17` or `17`; null for anything else. */
        fun parseId(id: String): Long? = id.trim().removePrefix("k").toLongOrNull()

        private val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS facts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scope TEXT NOT NULL, target TEXT NOT NULL, kind TEXT NOT NULL,
                text TEXT NOT NULL, words TEXT NOT NULL,
                task TEXT, run TEXT, verified_at TEXT,
                supersedes TEXT NOT NULL DEFAULT '[]', related TEXT NOT NULL DEFAULT '[]', anchors TEXT NOT NULL DEFAULT '[]',
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                status TEXT NOT NULL, stale_reason TEXT)""",
            "CREATE INDEX IF NOT EXISTS facts_scope ON facts(scope, target)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS facts_fts USING fts5(words, meta, tokenize = 'unicode61 remove_diacritics 2')",
        )
    }
}
