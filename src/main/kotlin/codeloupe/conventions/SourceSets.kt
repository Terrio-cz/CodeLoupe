package codeloupe.conventions

import java.nio.file.Files
import java.nio.file.Path

/** The source roots of a module: `src/main/{kotlin,java}` are main, every other set whose name says test (`test`, `testFixtures`, `scaleTest`) is test. */
object SourceSets {
    class Root(val dir: Path, val test: Boolean)

    fun of(module: Path): List<Root> {
        val src = module.resolve("src")
        if (!Files.isDirectory(src)) return emptyList()
        val roots = ArrayList<Root>()
        Files.newDirectoryStream(src).use { sets ->
            for (set in sets.sortedBy { it.fileName.toString() }) {
                val name = set.fileName.toString()
                val test = name.contains("test", ignoreCase = true)
                if (name != "main" && !test) continue
                for (language in listOf("kotlin", "java")) {
                    val dir = set.resolve(language)
                    if (Files.isDirectory(dir)) roots += Root(dir, test)
                }
            }
        }
        return roots
    }
}
