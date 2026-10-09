package codeloupe.lang.java

import codeloupe.lang.Languages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Half-typed code is what an agent leaves in a file between two edits: the good declarations must survive it. */
class JavaBrokenSourceTest {
    private fun names(source: String): List<String> {
        val facts = Languages.extract("B.java", source)!!
        return facts.decls.filter { !it.local }.map { (if (it.container.isEmpty()) "" else it.container + ".") + it.name }
    }

    @Test
    fun `a method without a name does not take the other declarations of the file with it`() {
        val source = "class B {\n    void good1() {}\n    void (int x) {}\n    void good2() {}\n}\n"
        val facts = Languages.extract("B.java", source)!!
        assertTrue(facts.errors > 0)
        assertEquals(listOf("B", "B.good1", "B.good2"), names(source))
    }

    @Test
    fun `a generic method without a name is skipped the same way`() {
        assertEquals(listOf("B", "B.after"), names("class B {\n    <T> void () {}\n    void after() {}\n}\n"))
    }
}
