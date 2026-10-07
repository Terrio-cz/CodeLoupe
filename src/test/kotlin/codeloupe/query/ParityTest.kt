package codeloupe.query

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.tools.ToolArgs
import codeloupe.tools.Tools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Output parity with the Node.js prototype (b7d0166): the golden files hold its find/outline/symbol answers on
 * the fixtures and on ten queries over TerrioImporter at a pinned commit.
 */
class ParityTest {
    @Test
    fun `fixture answers equal the prototype's`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
        assertGolden("fixture-golden.json", repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"))
    }

    /** Runs where TerrioImporter is checked out (CODELOUPE_TERRIO or the author's path); read only, from git objects. */
    @Test
    fun `Terrio answers equal the prototype's`() {
        val terrio = System.getenv("CODELOUPE_TERRIO") ?: "C:/Users/tadea/IdeaProjects/TerrioImporter"
        val golden = golden("terrio-golden.json")
        val commit = golden["commit"]!!.jsonPrimitive.content
        assumeTrue(Path.of(terrio).exists() && hasCommit(terrio, commit), "TerrioImporter at $commit is not available")
        assertGolden("terrio-golden.json", terrio, commit)
    }

    private fun assertGolden(name: String, repo: String, commit: String) {
        val db = TestRepos.tmpDir("parity").resolve("base.db")
        BaseBuilder.build(repo, commit, db)
        View(db).use { view ->
            for (case in golden(name)["results"]!!.jsonArray.map { it.jsonObject }) {
                val tool = Tools.named(case["tool"]!!.jsonPrimitive.content)!!
                val args = case["args"]!!.jsonObject
                assertEquals(case["text"]!!.jsonPrimitive.content, tool.run(view, ToolArgs(args)), "${tool.name} $args")
            }
        }
    }

    private fun golden(name: String): JsonObject =
        Json.parseToJsonElement(ParityTest::class.java.getResource("/parity/$name")!!.readText()).jsonObject

    private fun hasCommit(repo: String, commit: String): Boolean =
        ProcessBuilder("git", "-C", repo, "cat-file", "-e", "$commit^{commit}").start().waitFor() == 0
}
