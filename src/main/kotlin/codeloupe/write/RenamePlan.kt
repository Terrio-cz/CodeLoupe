package codeloupe.write

import codeloupe.query.DeclRow

/** A name in code to be written anew: a reference at a line and column of the index. */
internal data class RenameSite(val path: String, val line: Int, val col: Int)

/** A use the index could not settle (a `candidate`) or one the tool does not follow (a Java accessor of a Kotlin property): left to the caller. */
internal data class RenameCandidate(val path: String, val line: Int, val note: String)

/**
 * What a rename touches, read from the index: the [family] of declarations that move together (the target, what it overrides and what
 * overrides it, a class with its constructors), the exact [sites], the [imports] to rewrite, a [move] of the file when a public Java
 * type is renamed, and what is left to the caller.
 */
internal class RenamePlan(
    val oldName: String,
    val newName: String,
    val targets: List<DeclRow>,
    val family: List<DeclRow>,
    val sites: List<RenameSite>,
    val importPaths: Map<String, List<String>>,
    val candidates: List<RenameCandidate>,
    val warnings: List<String>,
    val move: Pair<String, String>?,
    /** Imported names that other declarations carry too (extensions or overloads of the same name in one package): their imports stay, a new one is added. */
    val sharedImports: Set<String> = emptySet(),
    /** Other declarations of the old name, and in how many lines of each file the index is sure they are used: they must still be after the rename. */
    val others: List<DeclRow> = emptyList(),
    val othersExact: Map<String, Int> = emptyMap(),
) {
    /** Where the file at [path] stands once the rename is done. */
    fun newPathOf(path: String): String = move?.takeIf { it.first == path }?.second ?: path
}
