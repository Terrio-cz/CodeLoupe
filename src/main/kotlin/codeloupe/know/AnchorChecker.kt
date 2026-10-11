package codeloupe.know

import kotlinx.coroutines.CancellationException

/**
 * Compares anchors with the code. An anchor fingerprinted at verification is stale when the signature or the body of its
 * declaration(s) differs now, or the declaration or file is gone; one never fingerprinted is stale only when the target is gone.
 */
class AnchorChecker(private val index: IndexReader) {
    /** The state of [anchors] now, one read of the index per checkout; a checkout that cannot be read gives [AnchorReading.unreachable]. */
    suspend fun readings(anchors: Collection<Anchor>): Map<Anchor, AnchorReading> {
        val out = HashMap<Anchor, AnchorReading>()
        for ((root, group) in anchors.distinct().groupBy { it.root }) {
            val read = try {
                index.read(root) { view -> group.map { AnchorProbe.read(view, it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A folder that is not a git repository has no index, but a cited file can still be looked for in it.
                val why = e.message.orEmpty().lineSequence().firstOrNull().orEmpty()
                group.map { AnchorProbe.onDiskOnly(it) ?: AnchorReading.unreachable(why) }
            }
            group.zip(read).forEach { (anchor, reading) -> out[anchor] = reading }
        }
        return out
    }

    /**
     * Anchors, not fingerprinted, for the names of [names] that the index of [root] resolves: a file name held by exactly one file, a class
     * declared somewhere. Nothing for a checkout that cannot be read.
     */
    suspend fun named(root: String, names: NamedCode): List<Anchor> {
        if (names.isEmpty) return emptyList()
        return try {
            index.read(root) { view ->
                val files = names.files.mapNotNull { name ->
                    view.filesBySuffix(name).filter { it == name || it.endsWith("/$name") }.distinct().singleOrNull()?.let { Anchor(root, it) }
                }
                files + names.classes.map { Anchor(root, symbol = it) }.filter { AnchorProbe.read(view, it).symbolFound }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** [anchor] with the digests of [reading]; unchanged when the target is not there to fingerprint. */
    fun fingerprint(anchor: Anchor, reading: AnchorReading): Anchor =
        if (reading.reachable && reading.fileFound && reading.symbolFound && reading.sig != null) anchor.copy(sig = reading.sig, body = reading.body) else anchor

    /** What the anchors say about [fact]: stale with the reasons, or standing (checked or not fingerprinted), or unknown when no checkout could be read. */
    fun verdict(fact: Fact, readings: Map<Anchor, AnchorReading>): Verdict {
        val stale = ArrayList<String>()
        var unknown = false
        for (anchor in fact.anchors) {
            val reading = readings[anchor] ?: continue
            when {
                !reading.reachable -> unknown = true
                anchor.path != null && !reading.fileFound -> stale += "file gone: ${anchor.path}"
                anchor.symbol != null && !reading.symbolFound -> stale += "declaration gone: ${anchor.label}"
                !anchor.fingerprinted || reading.sig == null -> Unit
                reading.sig != anchor.sig -> stale += "signature changed: ${anchor.label}"
                reading.body != anchor.body -> stale += "body changed: ${anchor.label}"
            }
        }
        return when {
            stale.isNotEmpty() -> Verdict.Stale(stale.take(MAX_REASONS).joinToString("; ") + if (stale.size > MAX_REASONS) " (+${stale.size - MAX_REASONS})" else "")
            unknown -> Verdict.Unknown
            else -> Verdict.Standing
        }
    }

    sealed interface Verdict {
        data class Stale(val reason: String) : Verdict
        data object Standing : Verdict
        data object Unknown : Verdict
    }

    private companion object {
        const val MAX_REASONS = 2
    }
}
