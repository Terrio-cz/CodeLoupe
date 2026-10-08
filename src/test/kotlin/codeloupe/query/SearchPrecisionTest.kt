package codeloupe.query

import codeloupe.TestRepos
import codeloupe.index.IndexedFile
import codeloupe.index.Store
import codeloupe.index.StoreWriter
import codeloupe.lang.Languages
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Concept questions about this repository's own sources (`search/codeloupe-questions.tsv`: expected path part, tab, question):
 * at least 80 % must find their declaration among the first five hits (CL-137).
 */
class SearchPrecisionTest {
    @Test
    fun `20 concept questions find their declaration in the top 5`() {
        val sources = Path.of("src/main/kotlin")
        val db = TestRepos.tmpDir("precision").resolve("base.db")
        Store.open(db).use { store ->
            store.autoCommit = false
            StoreWriter(store).use { writer ->
                Files.walk(sources).use { files ->
                    files.filter { it.extension == "kt" }.forEach { file ->
                        val path = file.toString().replace('\\', '/')
                        val text = file.readText()
                        writer.put(IndexedFile(path, "kotlin", path, text.length.toLong(), content = text), Languages.extract(path, text)!!)
                    }
                }
            }
            store.commit()
        }
        val questions = javaClass.getResource("/search/codeloupe-questions.tsv")!!.readText().lines().filter { it.isNotBlank() }.map { it.split('\t') }
        val misses = ArrayList<String>()
        View(db).use { view ->
            for ((expected, question) in questions) {
                val answer = FindQuery.run(view, FindQuery.Args(question, limit = 5, mode = "search"))
                if (answer.lines().none { expected in it }) misses += "$question [$expected]\n$answer"
            }
        }
        val precision = 100 * (questions.size - misses.size) / questions.size
        println("search precision at 5 on this repository: $precision % (${questions.size - misses.size}/${questions.size})")
        assertTrue(precision >= 80, "precision at 5 is $precision %, misses:\n" + misses.joinToString("\n\n"))
    }
}
