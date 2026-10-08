package codeloupe.query

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.index.BuildResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryTest {
    private val view = View(DB)

    @AfterTest
    fun close() = view.close()

    private fun find(q: String, kind: String? = null, module: String? = null) = FindQuery.run(view, FindQuery.Args(q, kind = kind, module = module))

    private fun symbol(name: String, full: Boolean = false) = SymbolQuery.run(view, SymbolQuery.Args(name, full = full))

    @Test
    fun `build indexes every Kotlin file of the commit`() {
        assertEquals(2, BUILT.files)
        assertEquals(0, BUILT.errors)
    }

    @Test
    fun `find - exact, qualified, glob, kind and module filters`() {
        assertContains(find("OrderService", kind = "class"), Regex("Constructs\\.kt:52-94 {2}class OrderService\\("))
        assertEquals(2, find("OrderService.handle").lines().size)
        assertContains(find("Order*", kind = "class"), "OrderService")
        assertContains(find("m1", module = "big"), "Big.kt")
        assertTrue(find("nothing_like_this").startsWith("no declaration"))
        assertFalse("fun local" in find("local"), "locals hidden by default")
    }

    @Test
    fun `outline of a file and of a type`() {
        val file = OutlineQuery.run(view, "shop/Constructs.kt")
        assertContains(file, Regex("^src/main/kotlin/com/example/shop/Constructs\\.kt {2}\\(\\d+ lines\\)"))
        assertContains(file, Regex("\n {2}68-75 {2}override fun handle\\(id: OrderId\\): Result"))
        val type = OutlineQuery.run(view, "OrderService")
        assertContains(type, Regex("\n {2}85-87 {2}companion object Factory\n {4}86 {2}fun create"))
        assertFalse("fun local" in type)
    }

    @Test
    fun `symbol - body with KDoc, overload selection, file-line, ambiguity`() {
        assertContains(symbol("total"), Regex("hash=[0-9a-f]{10}\n/\\*\\*\n \\* Computes the total\\.\n \\*/\nfun total"))
        assertTrue(symbol("OrderService.handle").startsWith("2 declarations match"))
        assertContains(symbol("OrderService.handle(OrderId, Boolean)"), "fun handle(id: OrderId, force: Boolean)")
        assertContains(symbol("OrderService.handle(_)"), "override fun handle(id: OrderId): Result {")
        assertContains(symbol("shop/Constructs.kt:70"), Regex("\\.handle {2}hash="))
        assertContains(symbol("String.shout"), "fun String.shout()")
        assertContains(symbol("OrderService.create"), "fun create(repo: Repository<Order>): OrderService", message = "a companion member by Type.member")
        assertTrue(symbol("com.example.shop.Registry").startsWith("src/main/kotlin/com/example/shop/Constructs.kt:96-99"))
        assertContains(symbol("`weird name`.`does something with spaces`"), "fun `does something with spaces`() = Unit")
        assertContains(symbol("Missing"), "data object Missing : Result")
    }

    @Test
    fun `large types collapse to header + members unless full=true`() {
        val summary = symbol("Big")
        assertContains(summary, "lines; members (symbol \"Big.<member>\" for one, full=true for all)")
        assertContains(summary, Regex("\n {2}\\d+-\\d+ {2}fun m39\\(x: Int\\): Int"))
        assertTrue(summary.length < 2500, "summary stays small (${summary.length})")
        assertContains(symbol("Big", full = true), "return x + 39")
    }

    private companion object {
        val REPO = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
        val DB = TestRepos.tmpDir("db").resolve("base.db")
        val BUILT: BuildResult = BaseBuilder.build(REPO.toString(), TestRepos.git(REPO, "rev-parse", "HEAD"), DB)
    }
}
