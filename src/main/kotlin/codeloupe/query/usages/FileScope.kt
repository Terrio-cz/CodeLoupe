package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.ImportRow

/** One file as the resolver sees it: package, imports, declarations by id and nesting, source lines. */
internal class FileScope(
    val path: String,
    val packageName: String,
    val imports: List<ImportRow>,
    decls: List<DeclRow>,
    content: String?,
) {
    private val byId = decls.associateBy { it.id }
    private val childrenOf = decls.groupBy { it.parentId }
    private val lines by lazy { content.orEmpty().split('\n') }

    fun decl(id: Long?): DeclRow? = id?.let(byId::get)

    fun children(d: DeclRow): List<DeclRow> = childrenOf[d.id].orEmpty()

    /** [d] and the declarations around it, innermost first. */
    fun chain(d: DeclRow?): List<DeclRow> = generateSequence(d) { decl(it.parentId) }.toList()

    fun line(n: Int): String = lines.getOrNull(n - 1)?.removeSuffix("\r").orEmpty()
}
