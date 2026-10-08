package codeloupe.query

/**
 * One instance for equal text: rows cached across requests repeat their path, kind, module and the like once per
 * row, which at tens of thousands of rows is most of the heap. Only for columns with few distinct values.
 */
internal fun String.pooled(): String = intern()
