package codeloupe.index

/** What the search index needs to know of one declaration; [declId] is its row in `decls`. */
data class SearchEntry(
    val declId: Long,
    val kind: String,
    val name: String,
    val container: String,
    val sig: String,
    val startLine: Int,
    val declLine: Int,
    val local: Boolean,
)
