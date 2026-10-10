package codeloupe.know

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CitedPathsTest {
    @Test
    fun `relative paths with a directory and a known extension are cited, with or without a line`() {
        assertEquals(listOf("app/src/Main.kt", "docs/plan.md"), CitedPaths.of("see app/src/Main.kt:88-90 and (docs/plan.md)"))
        assertEquals(listOf("build.gradle.kts".let { "sub/$it" }), CitedPaths.of("in sub/build.gradle.kts"))
        assertEquals(listOf("a/b.json", "a/b.jsonl"), CitedPaths.of("a/b.json a/b.jsonl"), "the longer extension is not cut short")
    }

    @Test
    fun `urls, absolute paths, elided ones and bare file names are not cited`() {
        assertEquals(emptyList(), CitedPaths.of("https://example.org/docs/page.md"))
        assertEquals(emptyList(), CitedPaths.of("C:/Users/me/work/Main.kt and ~/notes/a.md"))
        assertEquals(emptyList(), CitedPaths.of("public-api/.../Foo.kt"))
        assertEquals(emptyList(), CitedPaths.of("Main.kt and plan.md"))
    }

    @Test
    fun `an anchor text is a path when it has a directory or a known extension, else a symbol`() {
        assertEquals(AnchorRef(path = "a/B.kt"), AnchorRef.parse("a\\B.kt:12"))
        assertEquals(AnchorRef(path = "Billing.kt"), AnchorRef.parse("Billing.kt"))
        assertEquals(AnchorRef(symbol = "Billing.total"), AnchorRef.parse("Billing.total"))
        assertEquals(AnchorRef(path = "a/B.kt", symbol = "Billing.total"), AnchorRef.parse("a/B.kt#Billing.total"))
        assertEquals(AnchorRef(symbol = "total"), AnchorRef.parse("#total"))
        assertTrue(CitedPaths.looksLikePath("x/y"))
        assertFalse(CitedPaths.looksLikePath("Billing"))
    }
}
