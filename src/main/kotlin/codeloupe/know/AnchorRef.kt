package codeloupe.know

import kotlinx.serialization.Serializable

/**
 * An anchor as a writer names it. [root] is the checkout the path is relative to (the draft's root when null). Written as text
 * `path`, `path#Container.name` or `Container.name`; text that names a file by a directory or a known extension is a path.
 */
@Serializable
data class AnchorRef(val root: String? = null, val path: String? = null, val symbol: String? = null) {
    init {
        require(!path.isNullOrBlank() || !symbol.isNullOrBlank()) { "an anchor needs a path or a symbol" }
    }

    companion object {
        fun parse(text: String, root: String? = null): AnchorRef {
            val spec = text.trim()
            if ('#' in spec) return AnchorRef(root, CitedPaths.clean(spec.substringBefore('#')).ifEmpty { null }, spec.substringAfter('#').trim().ifEmpty { null })
            return if (CitedPaths.looksLikePath(spec)) AnchorRef(root, CitedPaths.clean(spec)) else AnchorRef(root, symbol = spec)
        }
    }
}
