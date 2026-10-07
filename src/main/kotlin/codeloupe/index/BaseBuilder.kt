package codeloupe.index

import codeloupe.git.BlobReader
import codeloupe.git.Git
import codeloupe.lang.FileFacts
import codeloupe.lang.Languages
import codeloupe.platform.IsoTime
import codeloupe.platform.Sha1
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Full build of a base index from the git objects of one commit, independent of any checkout. */
object BaseBuilder {
    // Syntax trees are walked recursively; a deep expression (thousands of `+` terms) needs far more stack
    // than a default thread has. The stack is reserved, not committed, so it costs no memory until used.
    private const val EXTRACT_STACK_BYTES = 256L * 1024 * 1024

    fun build(repoDir: String, commit: String, outFile: Path): BuildResult {
        val started = System.currentTimeMillis()
        val bySha = Git.lsTree(repoDir, commit).filter { Languages.languageOf(it.path) != null }.groupBy { it.sha }
        for (suffix in listOf("", "-wal", "-shm")) Files.deleteIfExists(Path.of("$outFile$suffix"))
        var files = 0
        var errors = 0
        val extractor = Executors.newSingleThreadExecutor { Thread(null, it, "codeloupe-extract", EXTRACT_STACK_BYTES).apply { isDaemon = true } }
        try {
            Store.open(outFile).use { db ->
                db.autoCommit = false
                StoreWriter(db).use { writer ->
                    BlobReader.read(repoDir, bySha.keys) { sha, text ->
                        val entries = bySha.getValue(sha)
                        val facts = extract(extractor, entries[0].path, text)
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
                db.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            }
        } finally {
            extractor.shutdownNow()
        }
        return BuildResult(ok = true, files = files, errors = errors, ms = System.currentTimeMillis() - started)
    }

    /** One file that cannot be read is indexed as a file with an error and no facts; it never fails the build. */
    private fun extract(extractor: ExecutorService, path: String, text: String): FileFacts = try {
        extractor.submit<FileFacts> { Languages.extract(path, text)!! }.get()
    } catch (e: ExecutionException) {
        System.err.println("codeloupe: $path indexed without facts: ${e.cause ?: e}")
        FileFacts("", emptyList(), emptyList(), emptyList(), errors = 1)
    }
}
