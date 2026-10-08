package codeloupe.reconcile

import kotlinx.serialization.Serializable

/** The answer of `GET /reconcile`: what the reconciler would do now. Nothing in it has been done. */
@Serializable
data class ReconcilePlan(
    val generatedAt: String,
    /** Whether the reconciler cleans on its own (`workspaces.reconcile.auto`). */
    val auto: Boolean,
    val counts: Map<String, Int> = emptyMap(),
    val entries: List<PlanEntry> = emptyList(),
    val problems: List<String> = emptyList(),
)
