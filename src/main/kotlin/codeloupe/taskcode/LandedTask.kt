package codeloupe.taskcode

import codeloupe.changes.DeclChange

/**
 * What the default branch holds of one task: its included commits oldest first, and which of them make up its
 * work — the merges that brought its branch in (each first-parent diff is the branch's whole change), or, landed
 * without one, every included commit.
 */
class LandedTask(val task: String, val commits: List<TaskCommit>, val mentionedOnly: List<TaskCommit>) {
    /** The commit that landed the task: its newest merge, else its newest commit. */
    val landing: TaskCommit? = commits.lastOrNull { it.merge } ?: commits.lastOrNull()

    val work: List<TaskCommit> = commits.filter { it.merge }.ifEmpty { commits }

    /** The files the work changed, by path: a file added then modified is added, modified then deleted is deleted, added then deleted is gone. */
    fun files(store: TaskCodeStore): Map<String, Char> {
        val status = LinkedHashMap<String, Char>()
        for (commit in work) {
            for (file in store.filesOf(commit.sha)) {
                val before = status[file.path]
                val after = when {
                    before == null -> file.status
                    before == 'A' && file.status == 'D' -> null
                    before == 'A' -> 'A'
                    file.status == 'D' -> 'D'
                    before == 'D' && file.status == 'A' -> 'M'
                    else -> before
                }
                if (after == null) status.remove(file.path) else status[file.path] = after
            }
        }
        return status
    }

    /**
     * The declarations the work changed, one per declaration: across commits a declaration added stays added, one
     * removed last is removed, a signature change outweighs a body change. Null when a work commit is too large.
     */
    fun decls(declsOf: (TaskCommit) -> List<CommitDecl>?): List<CommitDecl>? {
        val merged = LinkedHashMap<Triple<String, String, String>, CommitDecl>()
        for (commit in work) {
            for (decl in declsOf(commit) ?: return null) {
                val key = Triple(decl.path, decl.kind, decl.qualifiedName)
                val old = merged[key]
                merged[key] = when {
                    old == null -> decl
                    old.mark == DeclChange.ADDED && decl.mark == DeclChange.REMOVED -> { merged.remove(key); continue }
                    old.mark == DeclChange.ADDED -> decl.copy(mark = DeclChange.ADDED)
                    decl.mark == DeclChange.REMOVED -> decl
                    old.mark == DeclChange.SIGNATURE && decl.mark == DeclChange.BODY -> decl.copy(mark = DeclChange.SIGNATURE)
                    else -> decl
                }
            }
        }
        return merged.values.toList()
    }

    companion object {
        fun of(store: TaskCodeStore, task: String): LandedTask {
            val all = store.allCommitsOf(task)
            return LandedTask(task.uppercase(), all.filter { it.included }, all.filter { !it.included })
        }
    }
}
