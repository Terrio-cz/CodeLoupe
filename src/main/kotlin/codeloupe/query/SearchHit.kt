package codeloupe.query

/** A declaration the search index matched: its id in the database it came from and FTS5's own rank (lower is better). */
internal class SearchHit(val id: Long, val rank: Double)
