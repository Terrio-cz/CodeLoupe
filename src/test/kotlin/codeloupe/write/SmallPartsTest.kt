package codeloupe.write

import codeloupe.config.WriteConfig
import codeloupe.index.Extraction
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The small parts of the write tools: imports, indentation, globs, the policy, the source text, the atomic write. */
class SmallPartsTest {
    private fun addImports(path: String, text: String, vararg names: String): String {
        val facts = Extraction.extract(path, text)
        val edits = ImportEditor(text, if ("\r\n" in text) "\r\n" else "\n", path.endsWith(".java"), facts).add(names.toList())
        val result = TextEdits.apply(text, edits)
        assertEquals(0, Extraction.extract(path, result).errors, result)
        return result
    }

    @Test
    fun `imports go to their sorted place, once, and a missing block is made`() {
        val kotlin = "package p\n\nimport a.b.A\nimport a.b.C\n\nclass X\n"
        assertEquals("package p\n\nimport a.b.A\nimport a.b.B\nimport a.b.C\n\nclass X\n", addImports("X.kt", kotlin, "a.b.B"))
        assertEquals("package p\n\nimport a.b.A\nimport a.b.C\nimport a.b.D\n\nclass X\n", addImports("X.kt", kotlin, "a.b.D", "a.b.A"))
        assertEquals(kotlin, addImports("X.kt", kotlin, "a.b.A", "kotlin.collections.List", "p.Same"))
        assertEquals("package p\n\nimport a.b.D\n\nclass X\n", addImports("X.kt", "package p\n\nclass X\n", "a.b.D"))
        assertEquals("import a.b.D\n\nclass X\n", addImports("X.kt", "class X\n", "a.b.D"))
        val unsorted = "package p\n\nimport z.Z\nimport a.A\n\nclass X\n"
        assertEquals("package p\n\nimport z.Z\nimport a.A\nimport m.M\n\nclass X\n", addImports("X.kt", unsorted, "m.M"), "an unsorted block gets the import at its end")
        assertEquals("package p\r\n\r\nimport a.B\r\nimport a.C\r\n\r\nclass X\r\n", addImports("X.kt", "package p\r\n\r\nimport a.C\r\n\r\nclass X\r\n", "a.B"))
    }

    @Test
    fun `Java imports keep static imports in a group of their own`() {
        val java = "package p;\n\nimport java.util.List;\n\nclass X {\n}\n"
        val result = addImports("X.java", java, "java.util.Map", "static java.lang.Math.max")
        assertEquals("package p;\n\nimport java.util.List;\nimport java.util.Map;\n\nimport static java.lang.Math.max;\n\nclass X {\n}\n", result)
        assertEquals(java, addImports("X.java", java, "java.lang.String", "java.util.List"))
        assertFailsWith<WriteRefused> { addImports("X.java", java, "not an import") }
    }

    @Test
    fun `code is fitted to its place without losing the relative indentation`() {
        assertEquals("fun a() {\n        x()\n    }", Reindent.block("fun a() {\n    x()\n}", "    ", "\n"))
        assertEquals("fun a() {\r\n    x()\r\n}", Reindent.block("    fun a() {\n        x()\n    }\n\n", "", "\r\n"))
        assertEquals("fun a() {\n        x()\n    }", Reindent.block("fun a() {\n        x()\n    }", "    ", "\n"), "a first line cut from its text is measured by the closing brace")
        val raw = "val s = \"\"\"\n  keep\n   me\n\"\"\".trim()"
        assertEquals(raw, Reindent.block("    $raw", "        ", "\n"), "a raw string is left alone")
        assertFailsWith<WriteRefused> { Reindent.block("  \n \n", "", "\n") }
    }

    @Test
    fun `globs match whole paths`() {
        assertTrue(Glob.matches("**/.env", ".env") && Glob.matches("**/.env", "a/b/.env"))
        assertTrue(Glob.matches("src/*/Main.kt", "src/app/Main.kt") && !Glob.matches("src/*/Main.kt", "src/a/b/Main.kt"))
        assertTrue(Glob.matches("src/**", "src/a/b/c.kt") && Glob.matches("a?c.kt", "abc.kt") && !Glob.matches("a?c.kt", "a/c.kt"))
    }

    @Test
    fun `a glob that would take long to judge is answered at once and counts as a match`() {
        val path = "a/b/c/d/e/f/g/" + "x".repeat(40) + ".kt"
        val started = System.nanoTime()
        assertTrue(!Glob.matches("**/".repeat(40) + "no.kt", path), "forty folder stars are one, and this file is not no.kt")
        assertTrue(Glob.matches("*a".repeat(30) + "b", path), "too many wildcards: never weaker than a match")
        assertTrue(Glob.matches("**/**/**/" + "x".repeat(40) + ".kt", path), "a run of folder stars is one")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun `the policy refuses secrets, conflicts, links out of the worktree and everything but source`() {
        val root = Files.createTempDirectory("policy")
        val policy = WritePolicy(WriteConfig(deny = listOf("gen/**")))
        fun refusal(path: String, text: String? = null) = policy.refusal(root, root, path, text)
        assertNull(refusal("src/Main.kt"))
        assertTrue(refusal("../x.kt")!!.contains("not a path inside"))
        assertTrue(refusal("/etc/x.kt")!!.contains("not a path inside"))
        assertTrue(refusal("C:/x.kt")!!.contains("not a path inside"))
        assertTrue(refusal(".git/hooks/x.kt")!!.contains(".git"))
        assertTrue(refusal("build.gradle.kts")!!.contains("not a Kotlin or Java"))
        assertTrue(refusal("config/.env")!!.contains("not a Kotlin or Java"))
        assertTrue(refusal("gen/A.kt")!!.contains("denied"))
        assertTrue(refusal("src/Main.kt", "a\n<<<<<<< HEAD\nb\n=======\nc\n>>>>>>> x\n")!!.contains("conflict"))
        assertNull(refusal("src/Main.kt", "val a = \"=======\"\n"))
        assertTrue(refusal(".codeloupe.json")!!.contains("not edited"))
    }

    @Test
    fun `source text is kept exactly, and a file that is not UTF-8 is not touched`() {
        val dir = Files.createTempDirectory("source")
        val bom = dir.resolve("a.kt")
        Files.write(bom, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), 'b'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte()))
        val read = SourceText.read(bom)!!
        assertEquals("\uFEFFa\r\nb\r\n", read.text)
        assertEquals("\r\n", read.eol)
        assertTrue(read.bytes.contentEquals(Files.readAllBytes(bom)))
        assertEquals("\n", SourceText.of("a\nb\r\nc\n").eol)
        val latin = dir.resolve("b.kt")
        Files.write(latin, byteArrayOf('a'.code.toByte(), 0xE9.toByte()))
        assertFailsWith<WriteRefused> { SourceText.read(latin) }
        assertNull(SourceText.read(dir.resolve("none.kt")))
    }

    @Test
    fun `the atomic write replaces a file whole, keeps nothing behind and creates nothing else`() {
        val dir = Files.createTempDirectory("atomic")
        val file = dir.resolve("a.kt")
        file.writeText("old")
        AtomicWrite.replace(file, "new text".toByteArray())
        assertEquals("new text", Files.readString(file))
        assertEquals(listOf("a.kt"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
        val nested = dir.resolve("sub").also { it.createDirectories() }.resolve("b.kt")
        AtomicWrite.replace(nested, "x".toByteArray())
        assertEquals("x", Files.readString(nested))
    }
}
