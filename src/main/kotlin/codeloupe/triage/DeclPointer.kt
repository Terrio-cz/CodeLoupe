package codeloupe.triage

/** The declaration around a line of source: where it is, how a reader names it, and the `symbol` call and hash that read and edit it. */
data class DeclPointer(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    /** `[Container] fun name(…)`: the declaration named compactly. */
    val label: String,
    /** The name to pass to `symbol`: `Container.name`, or `name` at top level. */
    val symbol: String,
    val hash: String,
) {
    val key: String get() = "$path:$startLine"

    /** `path:3-9  [Container] fun name(…)`. */
    fun located(): String = "$path:${if (startLine == endLine) "$startLine" else "$startLine-$endLine"}  $label"

    /** `symbol Container.name hash=…`: what to run next. */
    fun next(): String = "symbol $symbol hash=$hash"
}
