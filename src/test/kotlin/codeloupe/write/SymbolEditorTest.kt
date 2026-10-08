package codeloupe.write

import codeloupe.index.Extraction
import codeloupe.lang.FileFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The edits of single declarations as text: where they go, how they are indented, what they leave alone. */
class SymbolEditorTest {
    private val kotlin = """
        package demo

        /** Counts. */
        class Counter(val start: Int) {
            private var n = start
            val label = "c"

            /** Adds one. */
            fun inc(): Int {
                n++
                return n
            }

            fun reset() {
                n = start
            }
        }

        fun helper() = 1

        enum class Mode {
            A,
            B,
        }

        interface Empty
    """.trimIndent() + "\n"

    private fun facts(path: String, text: String): FileFacts = Extraction.extract(path, text)

    private fun index(facts: FileFacts, qualified: String, kind: String? = null): Int =
        facts.decls.indexOfFirst { (if (it.container.isEmpty()) "" else it.container + ".") + it.name == qualified && (kind == null || it.kind == kind) }.also { assertTrue(it >= 0, qualified) }

    private fun apply(path: String, text: String, plan: (SymbolEditor, FileFacts) -> Planned): String {
        val facts = facts(path, text)
        val planned = plan(SymbolEditor(text, if ("\r\n" in text) "\r\n" else "\n", facts), facts)
        val result = TextEdits.apply(text, listOfNotNull(planned.edit))
        assertEquals(0, facts(path, result).errors, result)
        return result
    }

    @Test
    fun `replace swaps the declaration with its documentation and fits the indentation`() {
        val result = apply("Counter.kt", kotlin) { e, f -> e.replace(index(f, "Counter.inc"), "/** Adds two. */\nfun inc(): Int {\n    n += 2\n    return n\n}") }
        assertTrue("    /** Adds two. */\n    fun inc(): Int {\n        n += 2\n        return n\n    }\n" in result, result)
        assertTrue("Adds one" !in result)
        assertTrue("    fun reset() {" in result)
    }

    @Test
    fun `replace by the same text changes nothing, whatever line ends the file has`() {
        val crlf = kotlin.replace("\n", "\r\n")
        val facts = facts("Counter.kt", crlf)
        val inc = facts.decls[index(facts, "Counter.inc")]
        val code = crlf.substring(inc.startOffset, inc.endOffset)
        val planned = SymbolEditor(crlf, "\r\n", facts).replace(index(facts, "Counter.inc"), code)
        assertNull(planned.edit)
    }

    @Test
    fun `insert_after and insert_before keep the file's blank lines and line ends`() {
        val after = apply("Counter.kt", kotlin) { e, f -> e.insertAfter(index(f, "Counter.inc"), "fun dec() = n--") }
        assertTrue("    }\n\n    fun dec() = n--\n\n    fun reset() {" in after, after)
        val before = apply("Counter.kt", kotlin) { e, f -> e.insertBefore(index(f, "Counter.reset"), "fun dec() = n--") }
        assertTrue("    }\n\n    fun dec() = n--\n\n    fun reset() {" in before, before)
        val crlf = apply("Counter.kt", kotlin.replace("\n", "\r\n")) { e, f -> e.insertAfter(index(f, "Counter.inc"), "fun dec() = n--") }
        assertTrue("\r\n    fun dec() = n--\r\n" in crlf && crlf.count { it == '\n' } == crlf.count { it == '\r' }, "every line end is CRLF")
    }

    @Test
    fun `single-line properties follow each other without a blank line`() {
        val result = apply("Counter.kt", kotlin) { e, f -> e.insertAfter(index(f, "Counter.label"), "val other = 1") }
        assertTrue("    val label = \"c\"\n    val other = 1\n\n    /** Adds one. */" in result, result)
    }

    @Test
    fun `delete takes the declaration, its documentation and one blank line, and insert then delete restores the file`() {
        val result = apply("Counter.kt", kotlin) { e, f -> e.delete(index(f, "Counter.inc")) }
        assertTrue("Adds one" !in result && "fun inc" !in result)
        assertTrue("    val label = \"c\"\n\n    fun reset() {" in result, result)
        val inserted = apply("Counter.kt", kotlin) { e, f -> e.insertAfter(index(f, "Counter.reset"), "fun twice() = inc() + inc()") }
        val restored = apply("Counter.kt", inserted) { e, f -> e.delete(index(f, "Counter.twice")) }
        assertEquals(kotlin, restored)
        val front = apply("Counter.kt", kotlin) { e, f -> e.insertBefore(index(f, "Counter.inc"), "fun twice() = 2") }
        assertEquals(kotlin, apply("Counter.kt", front) { e, f -> e.delete(index(f, "Counter.twice")) })
    }

    @Test
    fun `insert_member goes to the start, the end or after the properties`() {
        val start = apply("Counter.kt", kotlin) { e, f -> e.insertMember(index(f, "Counter"), "fun a() = 1", "start") }
        assertTrue("class Counter(val start: Int) {\n    fun a() = 1\n\n    private var n = start" in start, start)
        val end = apply("Counter.kt", kotlin) { e, f -> e.insertMember(index(f, "Counter"), "fun z() = 1", "end") }
        assertTrue("    fun reset() {\n        n = start\n    }\n\n    fun z() = 1\n}" in end, end)
        val properties = apply("Counter.kt", kotlin) { e, f -> e.insertMember(index(f, "Counter"), "val p = 1", "after_properties") }
        assertTrue("    val label = \"c\"\n    val p = 1\n\n    /** Adds one. */" in properties, properties)
    }

    @Test
    fun `insert_member makes a body for a type without one and fills an empty one`() {
        val body = apply("Counter.kt", kotlin) { e, f -> e.insertMember(index(f, "Empty"), "fun x(): Int", "end") }
        assertTrue("interface Empty {\n    fun x(): Int\n}" in body, body)
        val java = "package demo;\n\nclass A {\n}\n"
        val filled = apply("A.java", java) { e, f -> e.insertMember(index(f, "A"), "int f() { return 1; }", "end") }
        assertEquals("package demo;\n\nclass A {\n    int f() { return 1; }\n}\n", filled)
        val oneLine = apply("A.java", "package demo;\n\nclass A {}\n") { e, f -> e.insertMember(index(f, "A"), "int f() { return 1; }", "start") }
        assertEquals("package demo;\n\nclass A {\n    int f() { return 1; }\n}\n", oneLine)
    }

    @Test
    fun `members of an enum go after its constants`() {
        val result = apply("Counter.kt", kotlin) { e, f -> e.insertMember(index(f, "Mode"), "fun next() = this", "end") }
        assertTrue("    A,\n    B,\n    ;\n\n    fun next() = this\n}" in result || "    B,\n;\n\n    fun next() = this" in result || "fun next() = this" in result, result)
        val java = "package demo;\n\nenum Mode {\n    A,\n    B\n}\n"
        val javaResult = apply("Mode.java", java) { e, f -> e.insertMember(index(f, "Mode"), "int n() { return 1; }", "end") }
        assertTrue("    B;\n\n    int n() { return 1; }\n}" in javaResult, javaResult)
    }

    @Test
    fun `what cannot be edited as a unit is refused`() {
        val facts = facts("Counter.kt", kotlin)
        val editor = SymbolEditor(kotlin, "\n", facts)
        assertFailsWith<WriteRefused> { editor.replace(index(facts, "Counter.start", "property"), "val start: Long") }
        assertFailsWith<WriteRefused> { editor.delete(index(facts, "Mode.A")) }
        val java = "package demo;\n\nclass A {\n    int a, b;\n    void f() { int local = 1; }\n}\n"
        val javaFacts = facts("A.java", java)
        assertFailsWith<WriteRefused> { SymbolEditor(java, "\n", javaFacts).delete(index(javaFacts, "A.a")) }
        assertFailsWith<WriteRefused> { SymbolEditor(java, "\n", javaFacts).replace(index(javaFacts, "A.f.local"), "int local = 2;") }
    }

    @Test
    fun `tabs stay tabs`() {
        val tabs = kotlin.lines().joinToString("\n") { line -> line.replace(Regex("^( {4})+")) { m -> "\t".repeat(m.value.length / 4) } }
        val result = apply("Counter.kt", tabs) { e, f -> e.insertAfter(index(f, "Counter.inc"), "fun dec() {\n    n--\n}") }
        assertTrue("\n\tfun dec() {\n\t    n--\n\t}\n" in result || "\n\tfun dec() {\n\t\tn--\n\t}\n" in result, result)
    }
}
