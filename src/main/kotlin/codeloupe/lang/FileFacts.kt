package codeloupe.lang

/** Everything the index knows about one file. Facts never reach outside the file. */
data class FileFacts(
    val packageName: String,
    val imports: List<ImportFact>,
    val decls: List<DeclFact>,
    val refs: List<RefFact>,
    val errors: Int,
)
