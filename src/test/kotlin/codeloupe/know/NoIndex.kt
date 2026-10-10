package codeloupe.know

import codeloupe.query.View

/** An index that cannot be read: facts without anchors never ask it, anchored ones come back unreachable. */
object NoIndex : IndexReader {
    override suspend fun <T> read(root: String, block: (View) -> T): T = throw IllegalStateException("no index here")
}
