package codeloupe.cli

import kotlinx.serialization.Serializable

/** What `repos add` did with each path: written to the configuration, already there, or refused. */
@Serializable
data class ReposAddReport(val added: List<String>, val already: List<String>, val rejected: List<Rejected>, val indexing: List<String>) {
    @Serializable
    data class Rejected(val path: String, val reason: String)

    fun lines(): List<String> =
        added.map { "added $it" } + already.map { "already listed $it" } + rejected.map { "skipped ${it.path}: ${it.reason}" } + indexing.map { "indexing $it" }
}
