package codeloupe.git

import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter

/**
 * Refs, commits and blobs of a repository, read in-process: the ref files first, then JGit, and git itself only
 * where JGit fails (a repository extension it does not know, a corrupt pack). Measured on Terrio: a git process costs
 * 35–100 ms, the same answer from an open JGit repository 1–10 ms.
 */
object GitObjects {
    /** The commit [name] names (`main`, `origin/main`, a tag, a sha), as git resolves `<name>^{commit}`; null when none. */
    fun resolve(commonDir: String, name: String): String? = RefReader.branch(commonDir, name) ?: jgitOr(
        commonDir,
        { it.resolve("$name^{commit}")?.name },
        { Git.run(commonDir, "rev-parse", "-q", "--verify", "$name^{commit}", allowFail = true)?.trim()?.ifEmpty { null } },
    )

    /** What the symbolic ref [ref] points to (`refs/remotes/origin/main`); null when it is not a symbolic ref. */
    fun symbolic(commonDir: String, ref: String): String? =
        if (RefReader.filesBackend(commonDir)) {
            RefReader.symbolic(commonDir, ref)
        } else {
            jgitOr(
                commonDir,
                { repo -> repo.exactRef(ref)?.takeIf { it.isSymbolic }?.target?.name },
                { Git.run(commonDir, "symbolic-ref", "-q", ref, allowFail = true)?.trim()?.ifEmpty { null } },
            )
        }

    /** A best common ancestor of commits [a] and [b]; null when they share no history (or a shallow clone cut it). */
    fun mergeBase(commonDir: String, a: String, b: String): String? = jgitOr(
        commonDir,
        { repo ->
            RevWalk(repo).use { walk ->
                walk.revFilter = RevFilter.MERGE_BASE
                walk.markStart(walk.parseCommit(ObjectId.fromString(a)))
                walk.markStart(walk.parseCommit(ObjectId.fromString(b)))
                walk.next()?.name
            }
        },
        { Git.run(commonDir, "merge-base", a, b, allowFail = true)?.trim()?.ifEmpty { null } },
    )

    /** Size in bytes of each blob the repository has; missing ones are left out. */
    fun blobSizes(commonDir: String, shas: Collection<String>): Map<String, Long> {
        if (shas.isEmpty()) return emptyMap()
        return jgitOr(
            commonDir,
            { repo ->
                repo.newObjectReader().use { reader ->
                    shas.mapNotNull { sha -> missingAsNull { sha to reader.getObjectSize(ObjectId.fromString(sha), Constants.OBJ_BLOB) } }.toMap()
                }
            },
            { Git.blobSizes(commonDir, shas) },
        )
    }

    /** Blob contents of the repository at [commonDir]; from the first blob JGit fails to read on, `git cat-file` reads the rest. */
    fun blobs(commonDir: String): BlobSource = BlobSource { shas, onBlob ->
        val all = shas.toList()
        var count = 0
        for ((i, sha) in all.withIndex()) {
            // Read outside onBlob: a failure there is the caller's, never a reason to read the blob again with git.
            val bytes = try {
                JGitRepos.read(commonDir) { repo ->
                    repo.newObjectReader().use { reader ->
                        missingAsNull { reader.open(ObjectId.fromString(sha), Constants.OBJ_BLOB).getCachedBytes(Int.MAX_VALUE) }
                    }
                }
            } catch (_: Exception) {
                return@BlobSource count + BlobReader.read(commonDir, all.subList(i, all.size), onBlob)
            } ?: continue
            onBlob(sha, bytes.toString(Charsets.UTF_8))
            count++
        }
        count
    }

    private inline fun <T> missingAsNull(block: () -> T): T? = try {
        block()
    } catch (_: MissingObjectException) {
        null
    }

    private fun <T> jgitOr(commonDir: String, jgit: (Repository) -> T, git: () -> T): T =
        try {
            JGitRepos.read(commonDir, jgit)
        } catch (_: Exception) {
            git()
        }
}
