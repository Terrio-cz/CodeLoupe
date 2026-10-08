package codeloupe.write

import codeloupe.TestRepos
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixtures the write tests edit have to compile as they are, or "still compiles" proves nothing. */
class FixturesCompileTest {
    @Test
    fun `the Kotlin fixture compiles`() {
        assertEquals(emptyList(), Compile.kotlin(TestRepos.FIXTURES.resolve("write/kotlin")))
    }

    @Test
    fun `the Java fixture compiles`() {
        assertEquals(emptyList(), Compile.java(TestRepos.FIXTURES.resolve("write/java")))
    }
}
