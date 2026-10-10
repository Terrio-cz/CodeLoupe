package codeloupe.metrics

/**
 * Tokens a run used with the instant of the assistant line that reported them. A [continuation] is a later line of a
 * message already counted: it carries only the output the message grew by, and is no turn of its own.
 */
data class UsageAt(val atMs: Long, val usage: Usage, val continuation: Boolean = false)
