package codeloupe.metrics

/** A code file an agent created in a run: [kind] `test` or `main`. */
data class NewFile(val path: String, val kind: String, val share: BoilerplateShare)
