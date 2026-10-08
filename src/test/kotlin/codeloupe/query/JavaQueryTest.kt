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

/** [QueryTest] on a Java fixture. */
class JavaQueryTest {
    private val view = View(DB)

    @AfterTest
    fun close() = view.close()

    private fun find(q: String, kind: String? = null, module: String? = null) = FindQuery.run(view, FindQuery.Args(q, kind = kind, module = module))

    private fun symbol(name: String, full: Boolean = false) = SymbolQuery.run(view, SymbolQuery.Args(name, full = full))

    @Test
    fun `build indexes every Java file of the commit`() {
        assertEquals(2, BUILT.files)
        assertEquals(0, BUILT.errors)
    }

    @Test
    fun `find - exact, qualified, glob, kind and module filters`() {
        assertContains(find("OrderService", kind = "class"), Regex("Constructs\\.java:87-161 {2}class OrderService extends BaseService implements AutoCloseable"))
        assertEquals(2, find("OrderService.handle").lines().size)
        assertContains(find("Order*", kind = "class"), "OrderService")
        assertContains(find("m1", module = "big"), "Big.java")
        assertContains(find("OrderService", kind = "constructor"), "OrderService(Repository<Order> repo, long seed)")
        assertContains(find("PAID", kind = "enum_entry"), "Constructs.java:58-63")
        assertContains(find("Marker", kind = "annotation"), "@interface Marker")
        assertTrue(find("nothing_like_this").startsWith("no declaration"))
        assertFalse("Order order" in find("order"), "locals hidden by default")
    }

    @Test
    fun `outline of a file and of a type`() {
        val file = OutlineQuery.run(view, "shop/Constructs.java")
        assertContains(file, Regex("^src/main/java/com/example/shop/Constructs\\.java {2}\\(\\d+ lines\\)?"))
        assertContains(file, "\n  111-123  Result handle(String id)")
        assertContains(file, "\n41-48  sealed interface Result permits Result.Ok, Result.Missing\n  42-43  record Ok(Order value) implements Result\n    42  Order value")
        assertContains(file, "\n  141-150  static <T extends Comparable<T>> T biggest(T first, T... rest)")
        val type = OutlineQuery.run(view, "OrderService")
        assertContains(type, "\n  152-156  class Audit\n    153-155  void record()")
        assertFalse("Order order" in type)
    }

    @Test
    fun `symbol - body with Javadoc, overload selection, file-line, ambiguity`() {
        assertContains(symbol("total"), Regex("hash=[0-9a-f]{10}\n {4}/\\*\\*\n {5}\\* Computes the total\\.\n {5}\\*/\n {4}static Money total"))
        assertTrue(symbol("OrderService.handle").startsWith("2 declarations match"))
        assertContains(symbol("OrderService.handle(String, boolean)"), "Result handle(String id, boolean force)")
        assertContains(symbol("OrderService.handle(_)"), "@Override\n    Result handle(String id) {")
        assertContains(symbol("shop/Constructs.java:116"), Regex("\\.handle {2}hash="))
        assertContains(symbol("OrderService.create"), "static OrderService create(Repository<Order> repo)")
        assertTrue(symbol("com.example.shop.Registry").startsWith("src/main/java/com/example/shop/Constructs.java:163-169"))
        assertContains(symbol("Status.PAID"), "PAID(\"paid\") {")
        assertContains(symbol("Result.Ok"), "record Ok(Order value) implements Result {")
        assertContains(symbol("biggest"), "@SafeVarargs\n    static <T extends Comparable<T>> T biggest(T first, T... rest) {")
    }

    @Test
    fun `large types collapse to header + members unless full=true`() {
        val summary = symbol("Big")
        assertContains(summary, "lines; members (symbol \"Big.<member>\" for one, full=true for all)")
        assertContains(summary, Regex("\n {2}\\d+-\\d+ {2}int m39\\(int x\\)"))
        assertTrue(summary.length < 2500, "summary stays small (${summary.length})")
        assertContains(symbol("Big", full = true), "return x + 39;")
    }

    private companion object {
        val REPO = TestRepos.fixtureRepo("java/sample", mapOf("big/src/main/java/com/example/big/Big.java" to bigClass("com.example.big", "Big", 40)))
        val DB = TestRepos.tmpDir("java-db").resolve("base.db")
        val BUILT: BuildResult = BaseBuilder.build(REPO.toString(), TestRepos.git(REPO, "rev-parse", "HEAD"), DB)

        /** A Java class with [n] small members - big enough to trigger the large-type summary. */
        fun bigClass(pkg: String, name: String, n: Int): String {
            val members = (0 until n).joinToString("\n") { i -> "    int m$i(int x) {\n        return x + $i;\n    }\n" }
            return "package $pkg;\n\n/** A big class. */\nclass $name {\n$members}\n"
        }
    }
}
