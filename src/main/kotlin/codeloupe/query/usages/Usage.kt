package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.RefRow

/** One reference to a target name, labelled, with the declaration a reader would open to see it. */
data class Usage(val ref: RefRow, val label: Label, val owner: DeclRow?)
