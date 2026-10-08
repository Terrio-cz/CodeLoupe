package codeloupe.ingest

/** The cost one run had in one hour, with what the savings need to know of the run: its role, its total cost and when it last wrote. */
data class HourUsage(val hour: Long, val role: String, val cost: Double, val runCost: Long, val endMs: Long)
