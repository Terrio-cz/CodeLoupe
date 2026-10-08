package codeloupe.changes

/**
 * A test class a change reaches. [name] is its qualified name; [nested] when the use was found in a class nested in it, so the
 * filter must take the nested classes too. [module] and [sourceSet] say which Gradle task runs it.
 */
internal data class TestClass(val module: String, val sourceSet: String, val name: String, val nested: Boolean) {
    /** Gradle project path of the module: `importers/ruian` is `:importers:ruian`, the root module `:`. */
    val project: String get() = if (module.isEmpty()) "" else ":" + module.replace('/', ':')

    val task: String get() = project + ":" + if (sourceSet == "test") "test" else sourceSet

    /** The argument of `--tests`. */
    val filter: String get() = if (nested) "$name*" else name

    val simpleName: String get() = name.substringAfterLast('.')
}
