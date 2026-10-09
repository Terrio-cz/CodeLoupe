package codeloupe.secrets

/**
 * Where a secret applies: everywhere, in one workspace (a Claude Code workspace folder) or in one repository. When a name
 * is set in several scopes the narrowest one wins: global < workspace < repository.
 */
data class SecretScope(val kind: Kind, val id: String? = null) {
    enum class Kind { GLOBAL, WORKSPACE, REPOSITORY }

    init {
        require((kind == Kind.GLOBAL) == (id == null)) { "scope ${kind.name.lowercase()} " + if (kind == Kind.GLOBAL) "takes no id" else "needs an id: ${kind.name.lowercase()}:<id>" }
        require(id == null || id.isNotBlank()) { "scope id is empty" }
    }

    val rank: Int get() = kind.ordinal

    /** This scope with the id in lower case, the way versions before CL-172 stored it; this scope itself when the id has no capitals. */
    fun legacy(): SecretScope = if (id == null || id == id.lowercase()) this else SecretScope(kind, id.lowercase())

    override fun toString(): String = when (kind) {
        Kind.GLOBAL -> "global"
        Kind.WORKSPACE -> "workspace:$id"
        Kind.REPOSITORY -> "repo:$id"
    }

    companion object {
        val GLOBAL = SecretScope(Kind.GLOBAL)

        /** Whether paths compare without regard to case here (Windows and macOS with its default volume); only then is an id lower-cased. */
        val FOLDS_CASE: Boolean = foldsCase(System.getProperty("os.name"))

        internal fun foldsCase(os: String): Boolean = os.lowercase().let { it.startsWith("windows") || it.startsWith("mac") }

        fun workspace(id: String, foldCase: Boolean = FOLDS_CASE) = SecretScope(Kind.WORKSPACE, normalize(id, foldCase))

        fun repository(id: String, foldCase: Boolean = FOLDS_CASE) = SecretScope(Kind.REPOSITORY, normalize(id, foldCase))

        /** `global`, `workspace:<id>` or `repo:<id>` (`repository:` too). */
        fun parse(text: String, foldCase: Boolean = FOLDS_CASE): SecretScope {
            val value = text.trim()
            if (value.equals("global", ignoreCase = true)) return GLOBAL
            val kind = value.substringBefore(':', "").lowercase()
            val id = value.substringAfter(':', "")
            return when (kind) {
                "workspace" -> workspace(id, foldCase)
                "repo", "repository" -> repository(id, foldCase)
                else -> throw IllegalArgumentException("scope must be global, workspace:<id> or repo:<id>, not '$text'")
            }
        }

        /** Forward slashes, no trailing slash, and lower case only where the file system folds case: `/x/Proj` and `/x/proj` are two folders on Linux. */
        private fun normalize(id: String, foldCase: Boolean) = id.trim().replace('\\', '/').trimEnd('/').let { if (foldCase) it.lowercase() else it }
    }
}
