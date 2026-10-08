package codeloupe.metrics

/** Tokens a run used with the instant of the assistant line that reported them. */
data class UsageAt(val atMs: Long, val usage: Usage)
