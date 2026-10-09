package codeloupe.write

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `replace_symbol` of Java overloads that differ only in how an array is written. */
class JavaOverloadEditTest {
    private val path = "src/main/java/demo/V.java"
    private val source = """
        package demo;

        class V {
            int foo(String s) { return 1; }
            int foo(String... s) { return 2; }
            int foo(String s[][]) { return 3; }
        }
    """.trimIndent() + "\n"

    private val harness by lazy { WriteHarness("write/java", extra = mapOf(path to source)) }

    @Test
    fun `each overload is replaced on its own, whichever way its array is written`() {
        for ((line, from, to) in listOf(Triple(4, "return 1;", "return 10;"), Triple(5, "return 2;", "return 20;"), Triple(6, "return 3;", "return 30;"))) {
            val name = "$path:$line"
            val read = harness.read(name)
            val answer = harness.blocking { harness.service.replace(harness.root, name, read.hash, read.code.replace(from, to)) }
            assertContains(answer, "replaced")
            assertContains(harness.text(path), to)
        }
        val text = harness.text(path)
        assertEquals(listOf("return 10;", "return 20;", "return 30;"), Regex("return \\d+;").findAll(text).map { it.value }.toList())
        assertFalse("return 1;" in text)
    }
}
