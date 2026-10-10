package codeloupe.know

import codeloupe.query.View
import codeloupe.repo.Registry

/** The daemon's index: the base of the repository `root` belongs to, with the overlay of that checkout. */
class RegistryIndex(private val registry: Registry) : IndexReader {
    override suspend fun <T> read(root: String, block: (View) -> T): T = registry.query(root, speculative = false, read = block)
}
