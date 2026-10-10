package codeloupe.know

import kotlinx.serialization.Serializable

/** One remembered fact: a line of text with its scope, kind, source, the code it is anchored to and how far it can still be trusted. */
@Serializable
data class Fact(
    val id: String,
    val scope: FactScope,
    val target: String,
    val kind: FactKind,
    val text: String,
    val source: FactSource = FactSource(),
    val verifiedAtCommit: String? = null,
    val supersedes: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val anchors: List<Anchor> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val status: FactStatus = FactStatus.ACTIVE,
    val staleReason: String? = null,
) {
    val scopeRef: ScopeRef get() = ScopeRef(scope, target)
}
