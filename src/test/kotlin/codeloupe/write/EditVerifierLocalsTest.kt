package codeloupe.write

import codeloupe.index.Extraction
import kotlin.test.Test
import kotlin.test.assertNull

/** Locals of a body are not declarations of the file: the same name in two sibling scopes is no duplicate. */
class EditVerifierLocalsTest {
    private fun replaceMethod(path: String, source: String, from: String, to: String): String? {
        val before = Extraction.extract(path, source)
        val method = before.decls.single { it.name == "m" && !it.local }
        val after = Extraction.extract(path, source.replace(from, to))
        return EditVerifier.verify(before, after, EditVerifier.Range(method.startOffset, method.endOffset, method.endOffset - method.startOffset + (to.length - from.length), true))
    }

    @Test
    fun `a Java method with two loops over the same variable name can be replaced`() {
        val source = "class V {\n    void m() {\n        for (int i = 0; i < 2; i++) {}\n        for (int i = 0; i < 3; i++) {}\n    }\n}\n"
        assertNull(replaceMethod("V.java", source, "i < 3", "i < 4"))
    }

    @Test
    fun `a Kotlin function with the same local name in two lambdas can be replaced`() {
        val source = "class V {\n    fun m() {\n        run { val x = 1 }\n        run { val x = 2 }\n    }\n}\n"
        assertNull(replaceMethod("V.kt", source, "val x = 2", "val x = 3"))
    }
}
