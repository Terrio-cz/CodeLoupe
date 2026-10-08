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

    override fun toString(): String = when (kind) {
        Kind.GLOBAL -> "global"
        Kind.WORKSPACE -> "workspace:$id"
        Kind.REPOSITORY -> "repo:$id"
    }

    companion object {
        val GLOBAL = SecretScope(Kind.GLOBAL)

        fun workspace(id: String) = SecretScope(Kind.WORKSPACE, normalize(id))

        fun repository(id: String) = SecretScope(Kind.REPOSITORY, normalize(id))

        /** `global`, `workspace:<id>` or `repo:<id>` (`repository:` too). */
        fun parse(text: String): SecretScope {
            val value = text.trim()
            if (value.equals("global", ignoreCase = true)) return GLOBAL
            val kind = value.substringBefore(':', "").lowercase()
            val id = value.substringAfter(':', "")
            return when (kind) {
                "workspace" -> workspace(id)
                "repo", "repository" -> repository(id)
                else -> throw IllegalArgumentException("scope must be global, workspace:<id> or repo:<id>, not '$text'")
            }
        }

        /** Paths and ids compare the same on every OS: forward slashes, no trailing slash, lower case. */
        private fun normalize(id: String) = id.trim().replace('\\', '/').trimEnd('/').lowercase()
    }
}
