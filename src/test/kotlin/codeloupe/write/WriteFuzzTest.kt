package codeloupe.write

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The second acceptance criterion: two hundred random writes of every kind - replace, insert before, after and into a type, delete,
 * add imports, create a file, rename - and the project still compiles. The sources are written by the tool alone; the compilers
 * are the real ones.
 */
class WriteFuzzTest {
    @Test
    fun `two hundred writes to the Kotlin project and it compiles`() = fuzz("write/kotlin", Variant.LF, seed = 37)

    @Test
    fun `two hundred writes to the Java project and it compiles`() = fuzz("write/java", Variant.LF, seed = 38)

    @Test
    fun `a hundred writes to CRLF Kotlin with a byte order mark and it compiles`() = fuzz("write/kotlin", Variant.BOM_CRLF, seed = 39, writes = 100)

    @Test
    fun `a hundred writes to CRLF Java and it compiles`() = fuzz("write/java", Variant.CRLF, seed = 40, writes = 100)

    private fun fuzz(fixture: String, variant: Variant, seed: Int, writes: Int = WRITES) {
        val h = WriteHarness(fixture, variant)
        val java = fixture.endsWith("java")
        val random = Random(seed)
        val generated = ArrayList<String>()
        val done = HashMap<String, Int>()
        var attempts = 0
        var written = 0
        var refused = 0
        while (written < writes && attempts < writes * 5) {
            attempts++
            val op = OPS[random.nextInt(OPS.size)]
            try {
                val label = step(h, java, random, op, written, generated) ?: continue
                done.merge(label, 1, Int::plus)
                written++
            } catch (e: WriteRefused) {
                refused++
                assertTrue(refusable(e.message.orEmpty()), "$fixture $op was refused: ${e.message}")
            }
            if (written % 100 == 0 && written > 0 && written != lastChecked) {
                lastChecked = written
                assertEquals(emptyList(), h.compileErrors(), "$fixture compiles after $written writes (${done})")
            }
        }
        assertEquals(writes, written, "$fixture: $refused of $attempts attempts were refused")
        assertTrue(OPS.all { done.keys.any { label -> label.startsWith(it) } }, "every kind of write was made: $done")
        assertEquals(emptyList(), h.compileErrors(), "$fixture compiles after $written writes ($done)")
        assertEquals(written, h.journal.read().size)
    }

    private var lastChecked = -1

    // What the tool may turn down: a place it does not edit as a unit. Anything else is a defect.
    private fun refusable(message: String) =
        listOf("shares its line", "declared together", "is a parameter of", "enum constant", "followed by other code", "has no body").any { it in message }

    private fun step(h: WriteHarness, java: Boolean, random: Random, op: String, n: Int, generated: MutableList<String>): String? {
        val all = h.declarations()
        val editable = all.filter { !it.decl.local && it.decl.kind in UNITS && !it.isHeaderParameter() }
        return when (op) {
            "replace" -> {
                val target = editable.random(random)
                val name = target.selector(all)
                val read = h.read(name)
                val annotated = annotate(read.code, target.decl.declStart - target.decl.start, java, random)
                changed(h.blocking { h.service.replace(h.root, name, read.hash, annotated) }, "replace")
            }
            "insert_member" -> {
                val types = all.filter { !it.decl.local && it.decl.kind in TYPES && it.decl.kind != "annotation" }
                val type = types.random(random)
                val name = type.selector(all)
                val member = member(java, type.decl.kind, n)
                h.blocking { h.service.insertMember(h.root, name, h.read(name).hash, member, listOf("start", "end", "after_properties").random(random)) }
                generated += "${type.decl.container.let { if (it.isEmpty()) "" else "$it." }}${type.decl.name}.fuzz$n"
                "insert_member"
            }
            "insert_after", "insert_before" -> {
                val anchor = editable.filter { it.decl.kind != "enum_entry" && it.decl.kind != "constructor" && it.decl.kind != "annotation" && !(it.parent?.kind == "annotation") }.random(random)
                val name = anchor.selector(all)
                val code = if (anchor.decl.container.isEmpty()) topLevel(java, n) else member(java, anchor.parent?.kind ?: "class", n)
                val read = h.read(name)
                h.blocking { if (op == "insert_after") h.service.insertAfter(h.root, name, read.hash, code) else h.service.insertBefore(h.root, name, read.hash, code) }
                generated += if (anchor.decl.container.isEmpty()) (if (java) "FuzzClass$n" else "fuzzTop$n") else "${anchor.decl.container}.fuzz$n"
                op
            }
            "delete" -> {
                val victim = generated.removeLastOrNull { name -> runCatching { h.read(name) }.isSuccess } ?: return null
                h.blocking { h.service.delete(h.root, victim, h.read(victim).hash) }
                "delete"
            }
            "add_imports" -> {
                val file = h.sources().random(random)
                val imports = (if (java) JAVA_IMPORTS else KOTLIN_IMPORTS).shuffled(random).take(1 + random.nextInt(3))
                changed(h.blocking { h.service.addImports(h.root, file, imports) }, "add_imports")
            }
            "create_file" -> {
                val (path, code) = newFile(h, java, n)
                h.blocking { h.service.createFile(h.root, path, code) }
                "create_file"
            }
            "rename" -> {
                val victim = generated.filter { it.substringAfterLast('.').startsWith("fuzz") && !it.substringAfterLast('.').startsWith("fuzzR") }.randomOrNull(random) ?: return null
                val read = runCatching { h.read(victim) }.getOrNull() ?: return null
                h.blocking { h.service.rename(h.root, victim, "fuzzR$n", read.hash, false) }
                generated[generated.indexOf(victim)] = if ('.' in victim) victim.substringBeforeLast('.') + ".fuzzR$n" else "fuzzR$n"
                "rename"
            }
            else -> null
        }
    }

    // The label of a write that changed a file; null for one that found everything as asked.
    private fun changed(answer: String, label: String): String? = label.takeUnless { answer.startsWith("unchanged") }

    // A line with an annotation that changes nothing, above the declaration's own first line.
    private fun annotate(code: String, below: Int, java: Boolean, random: Random): String {
        if (random.nextBoolean()) return code
        val lines = code.lines()
        val at = below.coerceIn(0, lines.size - 1)
        val indent = lines[at].takeWhile { it == ' ' || it == '\t' }
        val annotation = if (java) "@SuppressWarnings(\"unused\")" else "@Suppress(\"unused\")"
        if (lines[at].contains(annotation)) return code
        return (lines.take(at) + (indent + annotation) + lines.drop(at)).joinToString("\n")
    }

    private fun member(java: Boolean, ownerKind: String, n: Int): String = when {
        !java -> "fun fuzz$n(): Int = $n"
        ownerKind == "interface" -> "default int fuzz$n() {\n    return $n;\n}"
        else -> "int fuzz$n() {\n    return $n;\n}"
    }

    private fun topLevel(java: Boolean, n: Int): String = if (java) "class FuzzClass$n {\n}" else "fun fuzzTop$n(): Int = $n"

    private fun newFile(h: WriteHarness, java: Boolean, n: Int): Pair<String, String> =
        if (java) {
            val dir = "src/main/java/com/example/shop"
            "$dir/Fuzz$n.java" to "package com.example.shop;\n\npublic class Fuzz$n {\n    public int value() {\n        return $n;\n    }\n}\n"
        } else {
            val dir = "src/main/kotlin/com/example/bank"
            "$dir/Fuzz$n.kt" to "package com.example.bank\n\nfun created$n(): Int = $n\n"
        }

    private fun <T> MutableList<T>.removeLastOrNull(predicate: (T) -> Boolean): T? {
        val index = indexOfLast(predicate)
        return if (index < 0) null else removeAt(index)
    }

    private companion object {
        const val WRITES = 200
        val OPS = listOf("replace", "replace", "insert_member", "insert_member", "insert_after", "insert_before", "delete", "add_imports", "create_file", "rename")
        val UNITS = setOf("class", "interface", "object", "enum", "companion", "annotation", "fun", "property", "enum_entry")
        val TYPES = setOf("class", "interface", "object", "enum", "companion", "annotation")
        val KOTLIN_IMPORTS = listOf("java.util.UUID", "kotlin.math.max", "java.io.File", "java.time.Instant", "java.util.Locale", "kotlin.collections.ArrayDeque")
        val JAVA_IMPORTS = listOf("java.util.Set", "java.util.Map", "java.io.File", "java.time.Instant", "static java.lang.Math.max", "java.util.Optional")
    }
}
