package codeloupe.reconcile

import java.security.MessageDigest

/**
 * A fingerprint of what a plan would remove: the entries with the identity of their target and their verdict, nothing that changes with
 * time (when the plan was made, retry counters, the wording of a reason). A confirm carries the hash of the plan the person saw; if the
 * plan the daemon holds when the confirm arrives hashes differently, nothing is removed.
 */
object PlanHash {
    fun of(entries: List<PlanEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.map(::line).sorted().forEach { digest.update((it + "\n").toByteArray(Charsets.UTF_8)) }
        return digest.digest().take(LENGTH / 2).joinToString("") { "%02x".format(it) }
    }

    private fun line(e: PlanEntry) = listOf(e.key, e.kind.name, e.name, e.repo, e.workspace, e.ownership?.name, e.workspaceState?.name, e.verdict.name, e.released, e.path)
        .joinToString("|") { it?.toString().orEmpty().replace("|", "\\|") }

    private const val LENGTH = 32
}
