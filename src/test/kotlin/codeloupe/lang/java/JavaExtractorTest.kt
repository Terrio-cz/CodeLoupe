package codeloupe.lang.java

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

class JavaExtractorTest {
    private val facts = FACTS

    private fun decl(q: String, kind: String? = null): List<DeclFact> =
        facts.decls.filter { (if (it.container.isEmpty()) "" else it.container + ".") + it.name == q && (kind == null || it.kind == kind) }

    private fun one(q: String, kind: String? = null): DeclFact = decl(q, kind).also { assertEquals(1, it.size, "exactly one ${kind ?: ""} $q") }.single()

    @Test
    fun `package, imports, static imports and star imports`() {
        assertEquals("com.example.shop", facts.packageName)
        assertEquals(0, facts.errors)
        assertEquals(
            listOf(
                ImportFact("java.lang.Math.max", null, false), ImportFact("java.util.Collections", null, true),
                ImportFact("com.example.shop.model.Order", null, false), ImportFact("com.example.shop.model", null, true),
                ImportFact("com.example.util.Money", null, false), ImportFact("java.util.ArrayList", null, false),
                ImportFact("java.util.List", null, false), ImportFact("java.util.function.Function", null, false),
                ImportFact("java.util.function.Supplier", null, false),
            ),
            facts.imports,
        )
    }

    @Test
    fun `declaration kinds`() {
        one("Repository", "interface")
        one("Result", "interface")
        one("Result.Ok", "class")
        one("Result.Missing", "enum")
        one("Result.Missing.INSTANCE", "enum_entry")
        one("Marker", "annotation")
        one("Marker.level", "fun")
        one("Status", "enum")
        one("Status.PAID", "enum_entry")
        one("Status.PAID.next", "fun")
        one("OrderService.Audit", "class")
        one("Registry", "class")
        assertEquals(listOf("init", "init"), decl("OrderService.init", "init").map { it.name })
        assertEquals(listOf(true, false), decl("OrderService.init", "init").map { "static" in it.modifiers })
        assertEquals(2, decl("OrderService.OrderService", "constructor").size)
        one("Status.Status", "constructor")
    }

    @Test
    fun `methods - overloads, return types, parameters, varargs, generics`() {
        assertEquals(listOf(1, 2), decl("OrderService.handle", "fun").map { it.params.size }.sorted())
        assertEquals("Result", decl("OrderService.handle").first().returns)
        assertEquals(listOf(ParamFact("id", "String")), one("Repository.find").params)
        assertEquals("T", one("Repository.find").returns)
        assertEquals("void", one("Repository.save").returns)
        val biggest = one("OrderService.biggest")
        assertEquals(listOf(ParamFact("first", "T"), ParamFact("rest", "T[]", vararg = true)), biggest.params)
        assertEquals("static <T extends Comparable<T>> T biggest(T first, T... rest)", biggest.sig)
        assertEquals(listOf(ParamFact("args", "String[]")), one("Main.main").params)
        assertEquals("abstract Result handle(String id)", one("BaseService.handle").sig, "an abstract method ends before its semicolon")
    }

    @Test
    fun `fields, record components, modifiers, supertypes and annotations`() {
        assertEquals("Order", one("Result.Ok.value", "property").returns)
        assertTrue("private" in one("OrderService.clock", "property").modifiers)
        assertEquals("static final int MIN_ITEMS", one("Totals.MIN_ITEMS").sig, "the second declarator of `int A, B` shares type and modifiers")
        assertEquals(listOf("BaseService", "AutoCloseable"), one("OrderService", "class").supertypes)
        assertEquals(listOf("Result"), one("Result.Ok").supertypes)
        assertTrue("@Marker(value = \"service\", level = 2)" in one("OrderService", "class").modifiers)
        assertTrue("sealed" in one("Result").modifiers)
        assertEquals("sealed interface Result permits Result.Ok, Result.Missing", one("Result").sig)
        assertEquals("class OrderService extends BaseService implements AutoCloseable", one("OrderService", "class").sig)
    }

    @Test
    fun `@Override marks an override, as Kotlin's keyword does`() {
        assertTrue("override" in one("OrderService.close").modifiers)
        assertFalse("override" in one("OrderService.merge").modifiers)
        assertEquals("public void close()", one("OrderService.close").sig, "the marker is not part of the signature")
    }

    @Test
    fun `Javadoc extends the declaration range upwards`() {
        val total = one("Totals.total")
        assertEquals(19, total.start)
        assertEquals(22, total.declStart)
        assertEquals(14, one("Totals").start)
        assertEquals(16, one("Totals.MAX_ITEMS").start)
        assertEquals(17, one("Totals.MAX_ITEMS").declStart)
    }

    @Test
    fun `locals and anonymous-class members are local`() {
        assertTrue(one("OrderService.handle.order").local)
        assertTrue(one("Main.main.svc.<anonymous>.find").local)
        assertFalse(one("OrderService.log").local)
        assertFalse(one("OrderService.Audit.record").local)
    }

    @Test
    fun `enum constants with a body own its members, a record's components follow the record`() {
        val paid = facts.decls.indexOf(one("Status.PAID"))
        assertEquals(paid, one("Status.PAID.next").parent)
        val record = facts.decls.indexOf(one("Result.Ok"))
        assertEquals(record + 1, facts.decls.indexOf(one("Result.Ok.value")))
    }

    @Test
    fun `references - calls with receivers, constructors, method references, types, annotation elements`() {
        val calls = facts.refs.filter { it.kind == "call" }
            .map { "${if (it.recv != null) it.recv + "." else ""}${it.name}@${facts.decls.getOrNull(it.decl)?.name}" }
        for (c in listOf("repo.find@order", "svc.handle@main", "Registry.register@main", "OrderService.create@svc", "log@handle", "Result.Ok@handle", "max@bounded")) {
            assertTrue(c in calls, "missing call $c in $calls")
        }
        assertTrue(facts.refs.any { it.kind == "callable_ref" && it.name == "price" && it.recv == "Order" })
        assertTrue(facts.refs.any { it.kind == "type" && it.name == "Order" })
        assertTrue(facts.refs.any { it.kind == "named_arg" && it.name == "level" && it.recv == "Marker" })
        assertFalse(facts.refs.any { it.name == "null" }, "null literal is not a reference")
        assertFalse(facts.refs.any { it.name == "orders" && it.kind == "name" && it.line == 21 }, "parameter names are not references")
    }

    @Test
    fun `references - local bindings, receiver type specs, argument counts`() {
        fun ref(name: String, line: Int, kind: String? = null) = facts.refs.single { it.name == name && it.line == line && (kind == null || it.kind == kind) }
        assertEquals("Order", ref("order", 114).bind, "a local variable is a binding with its type")
        assertEquals("String", ref("id", 113).bind, "a parameter is a local binding with its type")
        assertEquals("@113:${ref("repo", 113).col}", ref("find", 113).recvType, "a field is typed by what it denotes")
        assertEquals("Order", ref("items", 118).recvType)
        assertEquals(2, ref("max", 118).args)
        assertEquals("@23:${ref("Money", 23, "name").col}", ref("ZERO", 23).recvType, "a name not bound in code: typed by what it denotes")
        assertEquals(null, ref("repo", 113).bind, "a field is not a local binding")
        assertEquals(null, ref("log", 121).bind, "a call never meets a binding")
    }

    @Test
    fun `references - lambda parameters get the type of the call or target`() {
        val text = """
            class A {
                void f(List<B> xs, Optional<B> o) {
                    xs.forEach(x -> x.g());
                    xs.stream().filter(y -> y.g());
                    Consumer<B> c = z -> z.g();
                    o.ifPresent(w -> w.g());
                    unknown.each(v -> v.g());
                }
            }
        """.trimIndent()
        val all = Languages.extract("A.java", text)!!.refs
        val refs = all.filter { it.name == "g" }.sortedBy { it.line }
        val stream = all.single { it.name == "stream" }
        assertEquals(listOf("x", "y", "z", "w", "v"), refs.map { it.recv })
        assertEquals("B", refs[0].recvType, "an element of the iterated collection")
        assertEquals("*@4:${stream.col}|List<B>", refs[1].recvType, "an element of what the stream call denotes, else of the collection")
        assertEquals("B", refs[2].recvType, "the declared functional interface")
        assertEquals("B", refs[3].recvType, "the value of an Optional")
        assertEquals("", refs[4].recvType, "a call that is not a known one: the query side reads its parameter")
    }

    @Test
    fun `anonymous classes are local objects with their supertype, also in a field initializer`() {
        val text = "interface I { void f(); }\nclass A {\n    I i = new I() { public void f() {} };\n}\n"
        val decls = Languages.extract("A.java", text)!!.decls
        val anonymous = decls.withIndex().single { it.value.name == "<anonymous>" }
        assertEquals("object", anonymous.value.kind)
        assertEquals(listOf("I"), anonymous.value.supertypes)
        assertTrue(anonymous.value.local)
        val member = decls.single { it.parent == anonymous.index }
        assertEquals("f", member.name)
        assertTrue(member.local)
        assertFalse(decls.single { it.name == "A" }.local)
    }

    @Test
    fun `a file with syntax errors still yields its declarations`() {
        val broken = Languages.extract("Broken.java", "package p;\nclass A {\n    void f( {\n    }\n    int g() { return 1; }\n}\n")!!
        assertTrue(broken.errors > 0)
        assertTrue(broken.decls.any { it.name == "A" })
    }

    @Test
    fun `CRLF and a BOM change only the hashes`() {
        val crlf = Languages.extract("Constructs.java", Char(0xFEFF) + TEXT.replace("\n", "\r\n"))!!
        assertEquals(0, crlf.errors)
        assertEquals(facts.decls.map { it.copy(hash = "") }, crlf.decls.map { it.copy(hash = "") })
        // The BOM is one column on line 1.
        assertEquals(facts.refs.filter { it.line > 1 }, crlf.refs.filter { it.line > 1 })
        assertEquals(facts.imports, crlf.imports)
    }

    @Test
    fun `language features of newer Java parse without errors`() {
        val text = """
            sealed interface S permits A, B {}
            record A(int x) implements S { A { if (x < 0) throw new IllegalArgumentException(); } }
            final class B implements S {
                String f(Object o) {
                    var list = new java.util.ArrayList<String>();
                    String t = ${"\"\"\""}
                        text
                        ${"\"\"\""};
                    return switch (o) {
                        case String s when s.isEmpty() -> "empty";
                        case A(int x) -> "a" + x;
                        default -> t + list.size();
                    };
                }
            }
        """.trimIndent()
        val parsed = Languages.extract("S.java", text)!!
        assertEquals(0, parsed.errors)
        assertEquals(listOf("S", "A", "B"), parsed.decls.filter { it.parent == -1 }.map { it.name })
        assertEquals(listOf(ParamFact("x", "int")), parsed.decls.single { it.kind == "constructor" }.params, "a compact constructor takes the components")
    }

    private companion object {
        val TEXT: String = Files.readString(TestRepos.FIXTURES.resolve("java/sample/src/main/java/com/example/shop/Constructs.java"))
        val FACTS: FileFacts = Languages.extract("Constructs.java", TEXT)!!
    }
}
