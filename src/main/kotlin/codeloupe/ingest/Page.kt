package codeloupe.ingest

/** One page of [items] out of [total]. */
data class Page<T>(val items: List<T>, val total: Int)
