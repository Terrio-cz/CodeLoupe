package codeloupe.triage

/** One compiler error line of a build output: where, and what the compiler said. */
data class BuildError(val path: String, val line: Int, val column: Int?, val message: String, val raw: String) {
    companion object {
        // Gradle's Kotlin: `e: src/Foo.kt:12:5 Unresolved reference 'x'`; kotlinc: `src/Foo.kt:12:5: error: …`; javac: `src/Foo.java:12: error: …`.
        private val GRADLE_KOTLIN = Regex("""^e: (.+?\.kts?):(\d+):(\d+):?\s+(.*)$""")
        private val KOTLINC = Regex("""^(.+?\.kts?):(\d+):(\d+): error: (.*)$""")
        private val JAVAC = Regex("""^(.+?\.java):(\d+): error: (.*)$""")

        fun parse(line: String): BuildError? {
            GRADLE_KOTLIN.find(line)?.let { return BuildError(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4], line) }
            KOTLINC.find(line)?.let { return BuildError(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4], line) }
            JAVAC.find(line)?.let { return BuildError(it.groupValues[1], it.groupValues[2].toInt(), null, it.groupValues[3], line) }
            return null
        }
    }
}
