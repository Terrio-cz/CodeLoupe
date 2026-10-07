package codeloupe.index

import codeloupe.git.BlobReader
import codeloupe.git.BlobSource
import codeloupe.git.Git
import codeloupe.lang.Languages
import codeloupe.platform.IsoTime
import codeloupe.platform.Sha1
import java.nio.file.Files
import java.nio.file.Path

/** Full build of a base index from the git objects of one commit, independent of any checkout. */
object BaseBuilder {
    fun build(repoDir: String, commit: String, outFile: Path): BuildResult {
        val started = System.currentTimeMillis()
        val bySha = Git.lsTree(repoDir, commit).filter { Languages.languageOf(it.path) != null }.groupBy { it.sha }
        for (suffix in listOf("", "-wal", "-shm")) Files.deleteIfExists(Path.of("$outFile$suffix"))
        var files = 0
        var errors = 0
        Store.open(outFile).use { db ->
            db.autoCommit = false
            StoreWriter(db).use { writer ->
                BlobReader.read(repoDir, bySha.keys) { sha, text ->
                    val entries = bySha.getValue(sha)
                    val facts = Extraction.extract(entries[0].path, text)
                    val hash = Sha1.hex(text)
                    for (entry in entries) {
                        writer.put(IndexedFile(entry.path, Languages.languageOf(entry.path)!!, hash, entry.size, content = text), facts)
                        files++
                        if (facts.errors > 0) errors++
                    }
                }
            }
            Store.setMeta(db, "schema", Store.SCHEMA_VERSION)
            Store.setMeta(db, "format", Store.FORMAT)
            Store.setMeta(db, "commit", commit)
            Store.setMeta(db, "built_at", IsoTime.now())
            Store.setMeta(db, "files", files)
            Store.setMeta(db, "files_with_errors", errors)
            db.commit()
            db.autoCommit = true
            Store.checkpoint(db)
        }
        return BuildResult(ok = true, files = files, errors = errors, ms = System.currentTimeMillis() - started)
    }

    /** Turns [outFile], a copy of an earlier base, into the base of [commit] by applying [update]: the files that differ. */
    fun update(blobs: BlobSource, commit: String, outFile: Path, update: StoreUpdate): BuildResult = Store.open(outFile).use { db ->
        val result = StoreUpdater.apply(db, update.copy(meta = update.meta + mapOf("commit" to commit, "built_at" to IsoTime.now())), blobs)
        Store.setMeta(db, "files", Store.count(db, "SELECT count(*) FROM files"))
        Store.setMeta(db, "files_with_errors", Store.count(db, "SELECT count(*) FROM files WHERE errors > 0"))
        Store.checkpoint(db)
        result
    }
}
