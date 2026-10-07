package codeloupe.index

import codeloupe.git.BlobSource
import codeloupe.lang.Languages
import codeloupe.platform.Sha1
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** Applies a [StoreUpdate] to a store in one transaction, so readers see all of it or none. */
object StoreUpdater {
    /** [blobs] reads the blobs of the update; files are read from disk. */
    fun apply(db: Connection, update: StoreUpdate, blobs: BlobSource?): BuildResult {
        val started = System.currentTimeMillis()
        db.autoCommit = false
        val batch = try {
            StoreWriter(db).use { writer ->
                Batch(writer).apply {
                    update.removes.forEach(writer::remove)
                    update.tombstones.forEach(writer::tombstone)
                    copy(db, update)
                    update.puts.filter { it.blob == null }.forEach(::putFile)
                    putBlobs(blobs, update.puts.filter { it.blob != null })
                }
            }.also {
                for ((key, value) in update.meta) Store.setMeta(db, key, value)
                db.commit()
            }
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
        return BuildResult(ok = true, files = batch.files, errors = batch.errors, ms = System.currentTimeMillis() - started, unread = batch.unread)
    }

    /** One update in progress: what was written and what could not be read. */
    private class Batch(private val writer: StoreWriter) {
        var files = 0
        var errors = 0
        val unread = ArrayList<String>()

        fun copy(db: Connection, update: StoreUpdate) {
            if (update.copies.isEmpty()) return
            Store.open(Path.of(update.copySource!!), readOnly = true).use { source ->
                StoreCopier(source, db).use { copier ->
                    for (entry in update.copies) {
                        writer.remove(entry.path)
                        if (copier.copy(entry.path, entry.size, entry.mtime)) files++ else putFile(entry)
                    }
                }
            }
        }

        fun putFile(entry: FilePut) {
            // Gone or locked since it was stamped: left as it was; the caller checks it again next time.
            val text = read(Path.of(entry.file!!))
            if (text == null) unread += entry.path else put(entry, text, entry.size)
        }

        fun putBlobs(blobs: BlobSource?, entries: List<FilePut>) {
            if (entries.isEmpty()) return
            val bySha = entries.groupBy { it.blob!! }
            blobs!!.read(bySha.keys) { sha, text ->
                for (entry in bySha.getValue(sha)) put(entry, text, text.toByteArray(Charsets.UTF_8).size.toLong())
            }
        }

        private fun put(entry: FilePut, text: String, size: Long) {
            val facts = Extraction.extract(entry.path, text)
            writer.put(IndexedFile(entry.path, Languages.languageOf(entry.path)!!, Sha1.hex(text), size, entry.mtime, text), facts)
            files++
            if (facts.errors > 0) errors++
        }

        private fun read(file: Path): String? = try {
            Files.readAllBytes(file).toString(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }
}
