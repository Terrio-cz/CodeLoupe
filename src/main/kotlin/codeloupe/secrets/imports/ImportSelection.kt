package codeloupe.secrets.imports

import codeloupe.secrets.SecretScope

/** One occurrence the user confirmed, by the id the inventory gave it, optionally under another scope than suggested. */
data class ImportSelection(val id: String, val scope: SecretScope? = null) {
    companion object {
        /** `ID` or `ID=scope`. */
        fun parse(text: String): ImportSelection {
            val id = text.substringBefore('=').trim()
            require(id.isNotEmpty()) { "an empty selection" }
            return ImportSelection(id, text.substringAfter('=', "").takeIf { it.isNotBlank() }?.let(SecretScope::parse))
        }
    }
}
