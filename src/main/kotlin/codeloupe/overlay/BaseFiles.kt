package codeloupe.overlay

import codeloupe.index.Store
import java.nio.file.Path

/** The files of a base index, read-only: which paths it has and their text. */
internal class BaseFiles(file: Path) : AutoCloseable {
    private val db = Store.open(file, readOnly = true)
    private val content = db.prepareStatement("SELECT content FROM files WHERE path = ?")
    private val exists = db.prepareStatement("SELECT 1 FROM files WHERE path = ?")

    fun paths(): Set<String> = db.createStatement().use { s ->
        s.executeQuery("SELECT path FROM files").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    fun has(path: String): Boolean {
        exists.setString(1, path)
        return exists.executeQuery().use { it.next() }
    }

    /** Text of [path] in the base, null when the base has no such file. */
    fun content(path: String): String? {
        content.setString(1, path)
        return content.executeQuery().use { if (it.next()) it.getString(1).orEmpty() else null }
    }

    override fun close() {
        content.close()
        exists.close()
        db.close()
    }
}
