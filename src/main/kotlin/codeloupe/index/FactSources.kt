package codeloupe.index

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Stores consulted before a file is parsed: one that holds the same path with the same content has its facts already. Opened
 * on first use, read-only; a store that is missing, unreadable, or built by another extractor is left out.
 */
internal class FactSources(private val paths: List<String>, private val target: Connection) : AutoCloseable {
    private class Source(val connection: Connection, val copier: StoreCopier)

    private val opened = ArrayList<Source>()
    private var loaded = false

    /** Copies [path] from the first store that has it with hash [hash]; false when none does. [path] must already be removed from the target. */
    fun copy(path: String, hash: String, size: Long, mtime: Long): Boolean {
        if (!loaded) open()
        return opened.any { runCatching { it.copier.copyIfSame(path, hash, size, mtime) }.getOrDefault(false) }
    }

    private fun open() {
        loaded = true
        for (p in paths) {
            val file = Path.of(p)
            if (!Files.exists(file)) continue
            runCatching {
                val connection = Store.open(file, readOnly = true)
                if (Store.getMeta(connection, "format") != Store.FORMAT) {
                    connection.close()
                    return@runCatching
                }
                opened += Source(connection, StoreCopier(connection, target))
            }
        }
    }

    override fun close() {
        for (source in opened) {
            runCatching { source.copier.close() }
            runCatching { source.connection.close() }
        }
    }
}
