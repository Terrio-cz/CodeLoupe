package codeloupe.lang.kotlin

import codeloupe.TestRepos
import codeloupe.lang.DeclFact
import codeloupe.lang.FileFacts
import codeloupe.lang.ImportFact
import codeloupe.lang.Languages
import codeloupe.lang.ParamFact
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinExtractorTest {
    private val facts = FACTS

    private fun decl(q: String, kind: String? = null): List<DeclFact> =
        facts.decls.filter { (if (it.container.isEmpty()) "" else it.container + ".") + it.name == q && (kind == null || it.kind == kind) }

    private fun one(q: String, kind: String? = null): DeclFact = decl(q, kind).also { assertEquals(1, it.size, "exactly one ${kind ?: ""} $q") }.single()

    @Test
    fun `package, imports, aliases and star imports`() {
        assertEquals("com.example.shop", facts.packageName)
        assertEquals(0, facts.errors)
        assertEquals(
            listOf(
                ImportFact("com.example.shop.model.Order", null, false), ImportFact("com.example.shop.model", null, true),
                ImportFact("com.example.util.Money", "Cash", false), ImportFact("kotlin.math.max", null, false),
            ),
            facts.imports,
        )
    }

    @Test
    fun `declaration kinds`() {
        one("OrderId", "typealias")
        one("Repository", "interface")
        one("Result", "interface")
        one("Result.Ok", "class")
        one("Result.Missing", "object")
        one("Status", "enum")
        one("Status.PAID", "enum_entry")
        one("OrderService.Factory", "companion")
        one("OrderService.Audit", "class")
        one("Registry", "object")
        one("OrderService.init", "init")
        one("OrderService.OrderService", "constructor")
        one("weird name", "class")
        one("weird name.does something with spaces", "fun")
    }

    @Test
    fun `functions - overloads, extensions, return types, parameters`() {
        assertEquals(listOf(1, 2), decl("OrderService.handle", "fun").map { it.params.size }.sorted())
        val shout = one("shout", "fun")
        assertEquals("String", shout.receiver)
        assertEquals("String", shout.returns)
        val big = one("isBig", "property")
        assertEquals("Order", big.receiver)
        assertEquals("Boolean", big.returns)
        assertEquals("OrderService", one("handle2", "fun").receiver)
        assertEquals(listOf(ParamFact("id", "OrderId")), one("Repository.find").params)
        assertEquals("T?", one("Repository.find").returns)
    }

    @Test
    fun `constructor properties, modifiers and supertypes`() {
        assertEquals("Order", one("Result.Ok.value", "property").returns)
        assertTrue("private" in one("OrderService.clock", "property").modifiers)
        assertEquals(listOf("BaseService", "AutoCloseable"), one("OrderService", "class").supertypes)
        assertTrue("@JvmInline" in one("Sku", "class").modifiers)
        assertTrue("const" in one("MAX_ITEMS").modifiers)
    }

    @Test
    fun `KDoc extends the declaration range upwards`() {
        val total = one("total")
        assertEquals(15, total.start)
        assertEquals(18, total.declStart)
        assertEquals(12, one("MAX_ITEMS").start)
    }

    @Test
    fun `locals and object-literal members are local`() {
        assertTrue(one("OrderService.handle.local").local)
        assertTrue(one("main.svc.<anonymous>.find").local)
        assertFalse(one("OrderService.log").local)
    }

    @Test
    fun `references - calls with receivers, infix, callable refs, types`() {
        val calls = facts.refs.filter { it.kind == "call" }
            .map { "${if (it.recv != null) it.recv + "." else ""}${it.name}@${facts.decls.getOrNull(it.decl)?.name}" }
        for (c in listOf("repo.find@order", "svc.handle@main", "svc.handle2@main", "Registry.register@main", "OrderService.create@svc", "log@record", "handle@handle2")) {
            assertTrue(c in calls, "missing call $c")
        }
        assertTrue(facts.refs.any { it.kind == "callable_ref" && it.name == "total" })
        assertTrue(facts.refs.any { it.kind == "type" && it.name == "OrderId" })
        assertFalse(facts.refs.any { it.name == "null" }, "null literal is not a reference")
        assertFalse(facts.refs.any { it.name == "orders" && it.kind == "name" && it.line == 18 && it.col < 15 }, "parameter names are not references")
    }

    @Test
    fun `references - local bindings, receiver type specs, argument counts`() {
        fun ref(name: String, line: Int) = facts.refs.single { it.name == name && it.line == line }
        assertEquals("List<Order>", ref("fold", 18).recvType)
        assertEquals(2, ref("fold", 18).args)
        assertEquals("OrderId", ref("id", 70).bind, "a parameter is a local binding with its type")
        assertEquals(
            "@69:${ref("find", 69).col}|*@69:${ref("repo", 69).col}", ref("id", 74).recvType,
            "val order = repo.find(id): what find denotes, else (stdlib find) an element of repo",
        )
        assertEquals("", ref("local", 74).bind, "a local function")
        assertEquals(1, ref("local", 74).args)
        assertEquals("@102:${ref("create", 102).col}", ref("handle", 107).recvType)
        assertEquals("@106:${ref("Registry", 106).col}", ref("register", 106).recvType, "a name not bound in code: typed by what it denotes")
        assertEquals(null, ref("repo", 69).bind, "a property is not a local binding")
    }

    @Test
    fun `references - lambda parameters and implicit receivers of standard library calls`() {
        val text = """
            class A {
                fun f(xs: List<B>) {
                    xs.forEach { it.g() }
                    xs.map { b -> b.g() }
                    B().apply { g() }
                    with(xs.first()) { g() }
                }
            }
        """.trimIndent()
        val refs = Languages.extract("A.kt", text)!!.refs.filter { it.name == "g" }.sortedBy { it.line }
        val first = Languages.extract("A.kt", text)!!.refs.single { it.name == "first" }
        assertEquals(listOf("B", "B", "B", "@${first.line}:${first.col}|B"), refs.map { it.recvType })
        assertEquals(listOf("it", "b", null, null), refs.map { it.recv })
    }

    @Test
    fun `CRLF and a BOM change only the hashes`() {
        val crlf = Languages.extract("Constructs.kt", Char(0xFEFF) + TEXT.replace("\n", "\r\n"))!!
        assertEquals(0, crlf.errors)
        assertEquals(facts.decls.map { it.copy(hash = "") }, crlf.decls.map { it.copy(hash = "") })
        // The BOM is one column on line 1.
        assertEquals(facts.refs.filter { it.line > 1 }, crlf.refs.filter { it.line > 1 })
        assertEquals(facts.imports, crlf.imports)
    }

    private companion object {
        val TEXT: String = Files.readString(TestRepos.FIXTURES.resolve("kotlin/sample/src/main/kotlin/com/example/shop/Constructs.kt"))
        val FACTS: FileFacts = Languages.extract("Constructs.kt", TEXT)!!
    }
}
