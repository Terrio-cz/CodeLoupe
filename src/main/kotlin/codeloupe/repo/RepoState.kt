package codeloupe.repo

import codeloupe.index.Store
import java.nio.file.Path

/** One repository the daemon knows. Mutable fields change under the instance lock. */
class RepoState(val id: String, val dir: Path, val commonDir: String, val defaultRef: String) {
    var baseCommit: String? = null
    var baseFile: Path? = null

    /** The base before the current one, kept until the next swap: overlays copy unchanged files' facts from it. */
    var previousCommit: String? = null
    var previousFile: Path? = null
    var lastBuild: LastBuild? = null
    var failure: BuildFailure? = null
    var failedAt: Long = 0

    /** The latest sync of the base to a newer commit of the default branch, and the commit it was started for. */
    var sync: BaseSync? = null
    var syncTarget: String? = null

    fun record(): RepoRecord = synchronized(this) {
        RepoRecord(id, commonDir, defaultRef, baseCommit, baseFile?.toString(), Store.FORMAT.takeIf { baseFile != null }, lastBuild)
    }

    fun summary(overlays: Int): RepoSummary = synchronized(this) { RepoSummary(id, commonDir, defaultRef, baseCommit, lastBuild, failure, overlays) }
}
