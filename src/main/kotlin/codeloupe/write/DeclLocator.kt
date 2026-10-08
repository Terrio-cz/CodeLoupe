package codeloupe.write

import codeloupe.lang.FileFacts
import codeloupe.query.DeclRow
import codeloupe.query.usages.Param
import kotlin.math.abs

/** Finds, in facts read from the file as it is now, the declaration an index row stands for: the index may be a moment behind the disk. */
internal object DeclLocator {
    fun find(facts: FileFacts, row: DeclRow): Int? {
        val wanted = "${row.kind}|${row.container}|${row.name}|${row.receiver.orEmpty()}|${Param.of(row).joinToString(",") { it.type.filterNot(Char::isWhitespace) }}"
        return facts.decls.indices
            .filter { facts.decls[it].local == row.local && DeclKeys.of(facts.decls[it]) == wanted }
            .minByOrNull { abs(facts.decls[it].declStart - row.declLine) }
    }
}
