package codeloupe.ingest

/**
 * What the ingest keeps in memory of a transcript file: its [size] and [mtime] when last looked at, [offset] bytes read, and the [tail] hash of
 * the bytes before the offset ([TailHash]). The parser state to continue from stays in the database ([RunWriter.snapshot]): one per transcript
 * of thousands, most of them finished, it was most of the daemon's heap (CL-238).
 */
class FileState(val size: Long, val mtime: Long, val offset: Long, val ter: String?, val tail: String? = null)
