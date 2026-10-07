package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.Format

/** The first line(s) of an answer: what it is about. */
internal object TargetLines {
    private const val MAX_TARGETS = 5
    private const val MAX_LISTED = 20

    /** Null when [targets] are one symbol (overloads, a class with its constructors), else the request to qualify. */
    fun ambiguity(query: String, targets: List<DeclRow>): String? =
        if (Targets.oneSymbol(targets)) null
        else "${targets.size} declarations match \"$query\" — qualify it (Type.member, pkg.Type, member(ParamType)):\n" +
            targets.take(MAX_LISTED).joinToString("\n", transform = Format::head) + Format.more(targets.size, MAX_LISTED)

    /** `usages of path:lines  signature`, or the first few of several declarations on lines of their own. */
    fun header(title: String, targets: List<DeclRow>): String =
        if (targets.size == 1) "$title of ${Format.head(targets[0])}"
        else "$title of ${targets.size} declarations:" + targets.take(MAX_TARGETS).joinToString("") { "\n  " + Format.head(it) } +
            Format.more(targets.size, MAX_TARGETS)
}
