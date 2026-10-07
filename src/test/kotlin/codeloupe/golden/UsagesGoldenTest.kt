package codeloupe.golden

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.query.Resolver
import codeloupe.query.View
import codeloupe.query.usages.HitLines
import codeloupe.query.usages.Label
import codeloupe.query.usages.UsageFinder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `usages` against a manually verified oracle: 44 TerrioImporter symbols at a pinned commit. Checks that every
 * `rg -w` code position is in the result, that `exact` hits are right and that no true usage is hidden as other.
 * Writes build/reports/codeloupe/golden-usages.md. Runs where TerrioImporter is checked out (CODELOUPE_TERRIO).
 */
class UsagesGoldenTest {
    @Test
    fun `usages of the golden Terrio symbols match the oracle`() {
        val oracle = Json.parseToJsonElement(UsagesGoldenTest::class.java.getResource("/golden/terrio-usages.json")!!.readText()).jsonObject
        val terrio = System.getenv("CODELOUPE_TERRIO") ?: "C:/Users/tadea/IdeaProjects/TerrioImporter"
        val commit = oracle.getValue("commit").jsonPrimitive.content
        assumeTrue(Path.of(terrio).exists() && hasCommit(terrio, commit), "TerrioImporter at $commit is not available")
        val db = TestRepos.tmpDir("golden").resolve("base.db")
        BaseBuilder.build(terrio, commit, db)
        val results = View(db).use { view -> measure(view, oracle) }
        val report = GoldenReport(results)
        writeReport(report.markdown(commit))
        assertEquals(1.0, report.superset, "superset of rg -w code positions")
        assertEquals(1.0, report.recall, "every true usage is exact or candidate")
        assertTrue(report.precision >= MIN_PRECISION, "exact precision ${report.precision}")
    }

    private fun measure(view: View, oracle: JsonObject): List<GoldenReport.Symbol> {
        val symbols = oracle.getValue("symbols").jsonArray.map { it.jsonObject }
        val targets = symbols.associate { s -> s.text("query") to Resolver.resolve(view, s.text("query")) }
        val baseline = IdentifierScan.positions(view, targets.values.flatten().map { it.name }.toSet())
        return symbols.map { s ->
            val query = s.text("query")
            val decls = targets.getValue(query)
            assertTrue(decls.isNotEmpty(), "\"$query\" resolves")
            val started = System.nanoTime()
            val usages = UsageFinder(view).usages(decls)
            val ms = (System.nanoTime() - started) / 1_000_000
            val found = usages.map { IdentifierScan.Position(it.ref.path, it.ref.line, it.ref.col) }.toSet()
            val expected = decls.map { it.name }.toSet().flatMap { baseline.getValue(it) }
            val lines = HitLines.lines(usages).groupBy({ it.label }, { "${it.ref.path}:${it.ref.line}" })
            GoldenReport.Symbol(
                query = query,
                kind = s.text("kind"),
                rgPositions = expected.size,
                covered = expected.count { it in found },
                exact = lines[Label.EXACT].orEmpty().toSet(),
                candidate = lines[Label.CANDIDATE].orEmpty().toSet(),
                oracle = s.getValue("usages").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                ms = ms,
            )
        }
    }

    private fun writeReport(text: String) {
        val dir = Path.of(System.getProperty("codeloupe.projectDir") ?: ".", "build", "reports", "codeloupe").createDirectories()
        dir.resolve("golden-usages.md").writeText(text)
        println(text)
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun hasCommit(repo: String, commit: String): Boolean =
        ProcessBuilder("git", "-C", repo, "cat-file", "-e", "$commit^{commit}").start().waitFor() == 0

    private companion object {
        const val MIN_PRECISION = 0.95
    }
}
