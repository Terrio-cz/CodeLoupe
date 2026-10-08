package codeloupe.ingest

/** What the ingest knows of a transcript file: its [size] and [mtime] when last looked at, [offset] bytes read, and the parser [state] to continue from. */
class FileState(val size: Long, val mtime: Long, val offset: Long, val ter: String?, val state: String?)
