package codeloupe.taskcode

/**
 * A commit of the default branch that mentions a task. [included] says whether its diff against [parent] (the first
 * parent) counts as the task's work: a merge counts only when the branch it merged in carried the same task, so a
 * branch's "merge origin/master" never attributes master's work to it.
 */
data class TaskCommit(val sha: String, val time: Long, val subject: String, val parent: String?, val merge: Boolean, val included: Boolean) {
    val short: String get() = sha.take(7)
}
