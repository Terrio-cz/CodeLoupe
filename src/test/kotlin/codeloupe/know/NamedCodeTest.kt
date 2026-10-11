package codeloupe.know

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamedCodeTest {
    @Test
    fun `bare file names and two-word class names are picked out of a line`() {
        val names = NamedCode.of("OrderService.kt calls PaymentGateway (see build.gradle.kts and `RetryPolicy.parse`) twice, also OrderService.kt")
        assertEquals(listOf("OrderService.kt", "build.gradle.kts"), names.files)
        assertEquals(listOf("PaymentGateway", "RetryPolicy"), names.classes)
    }

    @Test
    fun `names inside a path, a url or a line reference are not bare`() {
        assertTrue(NamedCode.of("see app/src/OrderService.kt:88 and https://example.org/docs/Page.md").files.isEmpty())
        assertTrue(NamedCode.of("in src/OrderService/Main.kt the code").classes.isEmpty())
        assertEquals(listOf("Main.kt"), NamedCode.of("Main.kt:88 holds it").files)
    }

    @Test
    fun `prose is not a class name`() {
        val names = NamedCode.of("Docker and Kotlin run on GitHub with TER-12, IOException, JSON and UTC")
        assertTrue(names.classes.filter { it != "GitHub" }.isEmpty(), names.classes.toString())
        assertTrue(NamedCode.of("a file named Foo.kt").classes.isEmpty(), "the class-looking part of a file name is the file's")
    }

    @Test
    fun `a line names only a handful of things`() {
        val text = (1..20).joinToString(" ") { "Name${it}Part" } + " " + (1..20).joinToString(" ") { "f$it.kt" }
        val names = NamedCode.of(text)
        assertEquals(NamedCode.MAX_PER_KIND, names.classes.size)
        assertEquals(NamedCode.MAX_PER_KIND, names.files.size)
    }
}
