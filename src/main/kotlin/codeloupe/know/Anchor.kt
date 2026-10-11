package codeloupe.know

import kotlinx.serialization.Serializable

/**
 * A fact's tie to code: a file, a declaration, or both. [sig] and [body] are digests of the declaration's signature and code
 * (of all declarations of the file for a file anchor) taken when the fact was last verified; null means never fingerprinted,
 * so only the existence of the file or declaration is checked.
 */
@Serializable
data class Anchor(val root: String, val path: String? = null, val symbol: String? = null, val sig: String? = null, val body: String? = null) {
    val fingerprinted: Boolean get() = sig != null && body != null

    /** The place the anchor points at, whatever it was fingerprinted to. */
    val place: Triple<String, String?, String?> get() = Triple(root, path, symbol)

    /** `path#Container.name`, `Container.name` or `path`. */
    val label: String get() = if (symbol == null) path.orEmpty() else if (path == null) symbol else "$path#$symbol"
}
