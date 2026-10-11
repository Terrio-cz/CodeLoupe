package codeloupe.brain

/** The brain CLI could not answer: node is missing, the run timed out, or `brain.mjs` failed. */
class BrainException(val kind: Kind, message: String) : RuntimeException(message) {
    enum class Kind(val code: String) { NODE_MISSING("node_missing"), TIMEOUT("timeout"), FAILED("failed") }
}
