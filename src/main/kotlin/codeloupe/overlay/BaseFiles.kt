package codeloupe.overlay

import codeloupe.index.Store
import java.nio.file.Path

/** The files of a base index, read-only: which paths it has and their text. */
internal class BaseFiles(file: Path) : AutoCloseable {
    private val db = Store.open(file, readOnly = true)
    private val content = db.prepareStatement("SELECT content FROM files WHERE path = ?")
    private val exists = db.prepareStatement("SELECT 1 FROM files WHERE path = ?")
    private val hash = db.prepareStatement("SELECT hash FROM files WHERE path = ?")

    fun paths(): Set<String> = db.createStatement().use { s ->
        s.executeQuery("SELECT path FROM files").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    /** How many files the base indexes. */
    fun count(): Int = db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM files WHERE deleted = 0").use { it.next(); it.getInt(1) } }

    fun has(path: String): Boolean {
        exists.setString(1, path)
        return exists.executeQuery().use { it.next() }
    }

    /** SHA-1 of [path]'s text in the base (as [codeloupe.platform.Sha1] computes it), null when the base has no such file. */
    fun hash(path: String): String? {
        hash.setString(1, path)
        return hash.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    /** Text of [path] in the base, null when the base has no such file. */
    fun content(path: String): String? {
        content.setString(1, path)
        return content.executeQuery().use { if (it.next()) it.getString(1).orEmpty() else null }
    }

    override fun close() {
        content.close()
        exists.close()
        hash.close()
        db.close()
    }
}
