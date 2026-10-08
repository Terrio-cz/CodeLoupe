package codeloupe.write

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The first acceptance criterion of the write tools: a declaration written back as it was read leaves its file byte for byte as it
 * was - LF, CRLF, a byte order mark, mixed line ends, no final newline, tabs - and a declaration changed and changed back does too.
 */
class RoundTripTest {
    @Test
    fun `Kotlin files in every layout are written back unchanged`() {
        for (variant in Variant.entries) roundTrip("write/kotlin", variant, minimum = 40)
    }

    @Test
    fun `Java files in every layout are written back unchanged`() {
        for (variant in listOf(Variant.LF, Variant.BOM_CRLF, Variant.MIXED, Variant.NO_FINAL_NEWLINE)) roundTrip("write/java", variant, minimum = 40)
    }

    @Test
    fun `a declaration changed and changed back leaves a file of consistent line ends as it was`() {
        for (fixture in listOf("write/kotlin", "write/java")) for (variant in listOf(Variant.LF, Variant.BOM_CRLF)) {
            val harness = WriteHarness(fixture, variant)
            val before = harness.snapshot()
            val all = harness.declarations()
            var restored = 0
            for (located in all.filter { !it.decl.local && it.decl.kind in CHANGEABLE && it.decl.end > it.decl.declStart && it.decl.kind != "constructor" }.filterIndexed { i, _ -> i % 2 == 0 }) {
                val name = located.selector(all)
                val read = harness.read(name)
                // A comment line before the last line is inside a declaration that has more than one line.
                val changed = read.code.lines().let { lines -> (lines.dropLast(1) + (lines.last().takeWhile { it == ' ' || it == '\t' } + "    // changed") + lines.last()).joinToString("\n") }
                val answer = try {
                    harness.blocking { harness.service.replace(harness.root, name, read.hash, changed) }
                } catch (_: WriteRefused) {
                    continue
                }
                val newHash = Regex("hash=([0-9a-f]{10})").find(answer)?.groupValues?.get(1) ?: error(answer)
                assertTrue("// changed" in harness.text(located.path), "${located.path} $name changed")
                harness.blocking { harness.service.replace(harness.root, name, newHash, read.code) }
                assertContentEquals(before.getValue(located.path), harness.bytes(located.path).toList(), "$fixture $variant $name")
                restored++
            }
            assertTrue(restored >= 8, "$fixture $variant restored $restored")
        }
    }

    private fun roundTrip(fixture: String, variant: Variant, minimum: Int) {
        val harness = WriteHarness(fixture, variant)
        val before = harness.snapshot()
        val all = harness.declarations()
        var unchanged = 0
        for (located in all.filterNot { it.decl.local }) {
            val name = located.selector(all)
            val code = located.code(harness.text(located.path))
            val answer = try {
                harness.blocking { harness.service.replace(harness.root, name, located.decl.hash, code) }
            } catch (e: WriteRefused) {
                assertTrue(located.isHeaderParameter(), "$fixture $variant $name was refused: ${e.message}")
                continue
            }
            assertContains(answer, "unchanged", message = "$fixture $variant $name: $answer")
            unchanged++
            assertContentEquals(before.getValue(located.path), harness.bytes(located.path).toList(), "$fixture $variant $name")
        }
        assertTrue(unchanged >= minimum, "$fixture $variant: only $unchanged declarations were written back")
        for (path in before.keys) assertContentEquals(before.getValue(path), harness.bytes(path).toList(), "$fixture $variant $path")
    }

    private companion object {
        val CHANGEABLE = setOf("class", "interface", "object", "fun", "property", "enum", "annotation", "companion")
    }
}
