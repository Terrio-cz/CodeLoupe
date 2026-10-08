package codeloupe.reconcile

import kotlinx.serialization.Serializable

/** The answer of `POST /reconcile/run`: what was attempted, and the plan that is left. */
@Serializable
data class ReconcileRun(
    val generatedAt: String,
    /** `start`, `interval`, `retry`, `job`, `manual`. */
    val trigger: String,
    val actions: List<ActionResult>,
    val remaining: ReconcilePlan,
)
