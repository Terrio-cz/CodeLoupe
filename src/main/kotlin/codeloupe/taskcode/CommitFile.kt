package codeloupe.taskcode

/** One file a commit changed against its first parent: [status] A, M or D, with the blobs on each side. */
data class CommitFile(val path: String, val status: Char, val oldBlob: String?, val newBlob: String?)
