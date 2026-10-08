package codeloupe.write

import codeloupe.index.Extraction
import codeloupe.index.IndexedFile
import codeloupe.index.Store
import codeloupe.index.StoreWriter
import codeloupe.lang.Languages
import codeloupe.platform.Sha1
import codeloupe.query.View
import java.nio.file.Files
import java.nio.file.Path

/**
 * A view of the worktree as it would be after a write, made without writing: the base index, with an overlay that holds the new texts
 * of the files to be written and the current texts of the files the worktree's own overlay holds. It lives for one question and is removed.
 */
internal object OverlayProbe {
    fun <T> with(view: View, worktree: Path, texts: Map<String, String>, removed: Set<String>, block: (View) -> T): T {
        val dir = Files.createTempDirectory("codeloupe-probe-")
        try {
            val file = dir.resolve("probe.db")
            Store.open(file).use { db ->
                db.autoCommit = false
                StoreWriter(db).use { writer ->
                    for ((path, text) in texts) put(writer, path, text)
                    for (path in removed) writer.tombstone(path)
                    for (path in view.overlayPaths() - texts.keys - removed) {
                        val current = SourceText.read(worktree.resolve(path))?.text
                        if (current == null) writer.tombstone(path) else if (Languages.languageOf(path) != null) put(writer, path, current)
                    }
                }
                Store.setMeta(db, "format", Store.FORMAT)
                db.commit()
                db.autoCommit = true
                Store.checkpoint(db)
            }
            return View(view.baseFile, file).use(block)
        } finally {
            runCatching { Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
        }
    }

    private fun put(writer: StoreWriter, path: String, text: String) {
        writer.put(IndexedFile(path, Languages.languageOf(path)!!, Sha1.hex(text), text.length.toLong(), content = text), Extraction.extract(path, text))
    }
}
