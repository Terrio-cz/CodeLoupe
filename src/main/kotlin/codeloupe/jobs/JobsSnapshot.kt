package codeloupe.jobs

import kotlinx.serialization.Serializable

/** Jobs in `/status`. */
@Serializable
data class JobsSnapshot(val running: Int, val queued: Int, val policyHook: Boolean, val slots: List<SlotSnapshot>)
