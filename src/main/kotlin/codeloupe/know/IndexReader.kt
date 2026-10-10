package codeloupe.know

import codeloupe.query.View

/** Read access to the code index of a checkout: what an anchor is resolved against. */
interface IndexReader {
    suspend fun <T> read(root: String, block: (View) -> T): T
}
