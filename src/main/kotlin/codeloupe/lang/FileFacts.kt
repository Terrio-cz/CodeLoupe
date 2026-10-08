package codeloupe.lang

import kotlinx.serialization.Serializable

/** Everything the index knows about one file. Facts never reach outside the file. */
@Serializable
data class FileFacts(
    val packageName: String,
    val imports: List<ImportFact>,
    val decls: List<DeclFact>,
    val refs: List<RefFact>,
    val errors: Int,
)
