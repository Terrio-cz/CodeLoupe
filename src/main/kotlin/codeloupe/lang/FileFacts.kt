package codeloupe.lang

import kotlinx.serialization.Serializable

/** Everything the index knows about one file. Facts never reach outside the file. [problems] are the first [errors], with their places. */
@Serializable
data class FileFacts(
    val packageName: String,
    val imports: List<ImportFact>,
    val decls: List<DeclFact>,
    val refs: List<RefFact>,
    val errors: Int,
    val problems: List<SyntaxProblem> = emptyList(),
)
