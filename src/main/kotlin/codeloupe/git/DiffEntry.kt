package codeloupe.git

/** One file of `git diff --raw -z`: [status] A, M, D, T…, [blob] the new content (null when deleted or not a blob). */
data class DiffEntry(val status: Char, val blob: String?, val path: String) {
    companion object {
        // Regular, executable and symlink files: what `git ls-tree` lists as blobs.
        private val BLOB_MODES = setOf("100644", "100755", "120000")
        private const val NO_BLOB = "0000000000000000000000000000000000000000"

        fun parse(raw: String): List<DiffEntry> {
            val fields = raw.split('\u0000')
            return buildList {
                var i = 0
                while (i + 1 < fields.size && fields[i].startsWith(":")) {
                    // `:<old mode> <new mode> <old sha> <new sha> <status>` then the path.
                    val (_, newMode, _, newSha, status) = fields[i].removePrefix(":").split(' ')
                    val blob = newSha.takeIf { newMode in BLOB_MODES && it != NO_BLOB }
                    add(DiffEntry(status[0], blob, fields[i + 1]))
                    i += 2
                }
            }
        }
    }
}
