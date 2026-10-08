package codeloupe.metrics

/**
 * A place where a CodeLoupe call did not do its job: [kind] `fallback` (the agent reached for rg/cat/Read on what it asked
 * for), `empty`, `busy` or `candidates` (references found, none exact). [shape] is the form of the query, [token] what was asked.
 */
data class Gap(val week: String, val tool: String, val shape: String, val kind: String, val token: String?)
