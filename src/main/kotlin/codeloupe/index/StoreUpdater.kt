package codeloupe.index

import codeloupe.git.BlobSource
import codeloupe.lang.FileFacts
import codeloupe.lang.Languages
import codeloupe.platform.ReadAhead
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
                FactSources(update.factSources, db).use { sources -> Batch(writer, sources).apply {
                    update.removes.forEach(writer::remove)
                    update.tombstones.forEach(writer::tombstone)
                    copy(db, update)
                    putFiles(update.puts.filter { it.blob == null })
                    putBlobs(blobs, update.puts.filter { it.blob != null })
                    finish()
                } }
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
        return BuildResult(ok = true, files = batch.files, errors = batch.errors, ms = System.currentTimeMillis() - started, unread = batch.unread, reused = batch.reused)
    }

    /** One update in progress: what was written and what could not be read. */
    private class Batch(private val writer: StoreWriter, private val sources: FactSources) {
        var files = 0
        var errors = 0
        var reused = 0
        val unread = ArrayList<String>()

        private val queue = ParseQueue()

        fun copy(db: Connection, update: StoreUpdate) {
            if (update.copies.isEmpty()) return
            Store.open(Path.of(update.copySource!!), readOnly = true).use { source ->
                StoreCopier(source, db).use { copier ->
                    for (entry in update.copies) {
                        queue.then {
                            writer.remove(entry.path)
                            if (copier.copy(entry.path, entry.size, entry.mtime)) files++ else putFile(entry)
                        }
                    }
                    queue.flush()
                }
            }
        }

        /** Writes what still waits for its parse. */
        fun finish() = queue.flush()

        fun putFiles(entries: List<FilePut>) = ReadAhead.each(entries, { it.size }, { read(Path.of(it.file!!)) }, ::putText)

        fun putFile(entry: FilePut) = putText(entry, read(Path.of(entry.file!!)))

        // Gone or locked since it was stamped: left as it was; the caller checks it again next time.
        private fun putText(entry: FilePut, text: String?) {
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
            val hash = Sha1.hex(text)
            // The same text at the same path has the same facts: a store that parsed it already hands them over.
            if (sources.has(entry.path, hash)) {
                queue.then(text.length) {
                    writer.remove(entry.path)
                    if (sources.copy(entry.path, hash, size, entry.mtime)) {
                        files++
                        reused++
                    } else {
                        write(entry, text, hash, size, Extraction.extract(entry.path, text))
                    }
                }
                return
            }
            queue.parse(ParseFile(entry.path, text)) { facts ->
                writer.remove(entry.path)
                write(entry, text, hash, size, facts)
            }
        }

        private fun write(entry: FilePut, text: String, hash: String, size: Long, facts: FileFacts) {
            writer.put(IndexedFile(entry.path, Languages.languageOf(entry.path)!!, hash, size, entry.mtime, text), facts)
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
