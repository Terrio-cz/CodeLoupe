package codeloupe.repo

import java.nio.file.Path

/** One repository the daemon knows. Mutable fields change under the instance lock. */
class RepoState(val id: String, val dir: Path, val commonDir: String, val defaultRef: String) {
    var baseCommit: String? = null
    var baseFile: Path? = null
    var lastBuild: LastBuild? = null
    var head: String? = null
    var headAt: Long = 0

    fun record(): RepoRecord = synchronized(this) {
        RepoRecord(id, commonDir, defaultRef, baseCommit, baseFile?.toString(), lastBuild)
    }
}
