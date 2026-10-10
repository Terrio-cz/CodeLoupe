package codeloupe.know

import codeloupe.query.DeclRow
import codeloupe.query.View
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Reads one anchor from a [View]: the declarations it names, or the ones the file holds. A file the index does not hold is looked up on disk. */
internal object AnchorProbe {
    fun read(view: View, anchor: Anchor): AnchorReading {
        val path = anchor.path
        val fileFound = path == null || view.file(path) != null || onDisk(anchor.root, path)
        val rows = declarations(view, anchor)
        return AnchorReading(
            fileFound = fileFound,
            symbolFound = anchor.symbol == null || rows.isNotEmpty(),
            sig = digest(rows.map { it.sig }),
            body = digest(rows.map { it.hash }),
        )
    }

    /** A path anchor in a folder that exists but has no index: whether the file is there. Null for a symbol anchor or a missing folder. */
    fun onDiskOnly(anchor: Anchor): AnchorReading? {
        val path = anchor.path ?: return null
        if (anchor.symbol != null || !runCatching { Files.isDirectory(Path.of(anchor.root)) }.getOrDefault(false)) return null
        return AnchorReading(fileFound = onDisk(anchor.root, path), symbolFound = true)
    }

    private fun declarations(view: View, anchor: Anchor): List<DeclRow> {
        val params = HashMap<String, Any?>()
        val where = StringBuilder("d.local = 0")
        anchor.path?.let {
            where.append(" AND f.path = :path")
            params["path"] = it
        }
        val symbol = anchor.symbol ?: return view.decls(where.toString(), params)
        val container = symbol.substringBeforeLast('.', "")
        val named = view.decls("$where AND d.name = :name AND d.container = :container", params + mapOf("name" to symbol.substringAfterLast('.'), "container" to container))
        // A qualified name, package included, names the declaration as well.
        return named.ifEmpty { view.decls("$where AND d.fqn = :fqn", params + ("fqn" to symbol)) }
    }

    private fun onDisk(root: String, path: String): Boolean = runCatching { Files.isRegularFile(Path.of(root).resolve(path)) }.getOrDefault(false)

    /** Order does not matter: the same declarations in another order are the same file. */
    private fun digest(parts: List<String>): String? {
        if (parts.isEmpty()) return null
        val bytes = MessageDigest.getInstance("SHA-256").digest(parts.sorted().joinToString("\u0000").toByteArray())
        return bytes.take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
    }

    private const val DIGEST_BYTES = 8
}
