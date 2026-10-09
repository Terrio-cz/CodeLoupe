package codeloupe.changes

/**
 * A test class a change reaches. [name] is its qualified name; [nested] when the use was found in a class nested in it, so the
 * filter must take the nested classes too. [module] and [sourceSet] say which Gradle task runs it.
 */
internal data class TestClass(val module: String, val sourceSet: String, val name: String, val nested: Boolean) {
    /** Gradle project path of the module: `importers/ruian` is `:importers:ruian`, the root module `:`. */
    val project: String get() = if (module.isEmpty()) "" else ":" + module.replace('/', ':')

    val task: String get() = project + ":" + taskOf(sourceSet)

    /** The argument of `--tests`. */
    val filter: String get() = if (nested) "$name*" else name

    val simpleName: String get() = name.substringAfterLast('.')

    companion object {
        /**
         * The Gradle task that runs a source set's tests: the source set's own name for the custom ones the JVM test suites make
         * (`integrationTest`), `allTests` for Kotlin Multiplatform's `commonTest`, and `test` for what only supports tests
         * (`testFixtures` has no task of its own: the module's tests use it).
         */
        fun taskOf(sourceSet: String): String = when (sourceSet) {
            "", "test", "testFixtures" -> "test"
            "commonTest" -> "allTests"
            else -> sourceSet
        }
    }
}
