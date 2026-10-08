package codeloupe.doc

/**
 * A part of a [Doc] a reader can name: [handle] is what `section=` accepts. [own] is the text under the heading up to
 * the next heading of any level, [body] runs on through the sub-headings; both start with the heading line.
 */
class DocSection(val handle: String, val title: String, val level: Int, val startLine: Int, val own: String, val body: String) {
    val lines: Int get() = body.lines().count { it.isNotBlank() }
    val ownHash: String by lazy { DocHash.of(own) }
    val bodyHash: String by lazy { DocHash.of(body) }
}
