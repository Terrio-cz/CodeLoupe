package codeloupe.ingest

/** What the calls of one category cost in a run: how many, how big, how long carried, what keeping them in context costs. */
data class CategoryCost(val category: String, val calls: Int, val chars: Long, val carried: Long, val weighted: Long, val errors: Int)
