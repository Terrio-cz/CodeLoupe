package codeloupe.query

/**
 * The `module` option of the queries as a SQL condition on alias `f`: a module (`accounts`, and the modules under it), or any
 * directory or file-name prefix of the paths (`accounts/src/main`, `app/src/main/kotlin/demo/Ledger`), since callers name the
 * directory they mean as often as the module.
 */
internal object ModuleScope {
    fun sql(): String = "(f.module = :module OR f.module LIKE :modulePrefix ESCAPE '\\' OR f.path LIKE :modulePrefix ESCAPE '\\' OR f.path LIKE :moduleFile ESCAPE '\\')"

    fun params(module: String): Map<String, Any?> {
        val prefix = module.trim().replace('\\', '/').trimEnd('/')
        return mapOf("module" to prefix, "modulePrefix" to Like.escape(prefix) + "/%", "moduleFile" to Like.escape(prefix) + ".%")
    }
}
