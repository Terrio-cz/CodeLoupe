package codeloupe.lang.java

import codeloupe.lang.DeclFact
import codeloupe.lang.Languages
import codeloupe.lang.ParamFact
import codeloupe.write.DeclKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Overloads that differ only in how an array is written are different declarations; an implicit class has its file's name. */
class JavaOverloadKeysTest {
    private fun facts(path: String, text: String) = Languages.extract(path, text)!!

    private fun qualified(d: DeclFact) = (if (d.container.isEmpty()) "" else d.container + ".") + d.name

    private val overloads = """
        package p;

        class V {
            void foo(String s) {}
            void foo(String... s) {}
            void foo(String s[]) {}
            void foo(int[]... grid) {}
            int c = 1, d[] = {};
            int[] x, y[];
        }
    """.trimIndent() + "\n"

    @Test
    fun `varargs and C-style dimensions are arrays in the type and in the key`() {
        val foos = facts("V.java", overloads).decls.filter { it.name == "foo" }
        assertEquals(
            listOf(
                listOf(ParamFact("s", "String")),
                listOf(ParamFact("s", "String[]", vararg = true)),
                listOf(ParamFact("s", "String[]")),
                listOf(ParamFact("grid", "int[][]", vararg = true)),
            ),
            foos.map { it.params },
        )
        val keys = foos.map(DeclKeys::of)
        assertEquals(keys.size - 1, keys.toSet().size, "the String[] and String... overloads cannot both exist, so they share a key")
        assertEquals(keys[1], keys[2])
        assertTrue(keys[0] != keys[1])
    }

    @Test
    fun `a variable with dimensions after its name is an array`() {
        val types = facts("V.java", overloads).decls.filter { it.kind == "property" }.associate { it.name to it.returns }
        assertEquals(mapOf("c" to "int", "d" to "int[]", "x" to "int[]", "y" to "int[][]"), types)
    }

    @Test
    fun `a parameter with dimensions after its name is typed as an array inside the body`() {
        val facts = facts("V.java", "class V {\n    int f(String s[]) { return s.length; }\n}\n")
        assertEquals(listOf(ParamFact("s", "String[]")), facts.decls.single { it.name == "f" }.params)
    }

    @Test
    fun `an implicit class is named after its file, with the members and classes inside it`() {
        val source = "package p;\n\nclass A { void a() {} }\n\nvoid main() {}\n"
        val names = facts("src/p/Main.java", source).decls.map(::qualified)
        assertTrue("Main" in names, names.toString())
        assertTrue("Main.main" in names, names.toString())
        assertTrue("Main.A" in names, names.toString())
        assertTrue(names.none { "?" in it }, names.toString())
        assertEquals(listOf("Other.main"), facts("C:\\w\\Other.java", "void main() {}\n").decls.filter { it.kind == "fun" }.map(::qualified))
    }

    @Test
    fun `a class without a name declares nothing and keeps its members out of the container`() {
        val names = facts("B.java", "class B {\n    class {\n        void inner() {}\n    }\n    void after() {}\n}\n").decls.map(::qualified)
        assertTrue(names.none { "?" in it }, names.toString())
        assertTrue("B.after" in names, names.toString())
    }
}
