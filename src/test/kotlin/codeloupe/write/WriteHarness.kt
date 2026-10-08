package codeloupe.write

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.WriteConfig
import codeloupe.daemon.JobQueue
import codeloupe.index.Extraction
import codeloupe.lang.DeclFact
import codeloupe.lang.Languages
import codeloupe.query.SymbolQuery
import codeloupe.repo.Registry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

/** How the fixture's files are laid out on disk: the write tools must keep every one of them as it is. */
enum class Variant(val transform: (String) -> String) {
    LF({ it }),
    CRLF({ it.replace("\n", "\r\n") }),
    BOM_CRLF({ "﻿" + it.replace("\n", "\r\n") }),
    MIXED({ text -> text.removeSuffix("\n").split("\n").withIndex().joinToString("") { (i, line) -> line + (if (i % 3 == 2) "\n" else "\r\n") } }),
    NO_FINAL_NEWLINE({ it.trimEnd('\n') }),
    TABS({ text -> text.lines().joinToString("\n") { line -> line.replace(Regex("^( {4})+")) { m -> "\t".repeat(m.value.length / 4) } } }),
}

/** A declaration of the harness's repository: where it is, and what it sits in. */
class Located(val path: String, val packageName: String, val decl: DeclFact, val parent: DeclFact?) {
    /** A name that reaches the declaration alone: its qualified name when nothing else in [all] has it, else its first line. */
    fun selector(all: List<Located>): String {
        val qualified = listOf(packageName, decl.container, decl.name).filter { it.isNotEmpty() }.joinToString(".")
        val alone = all.count { listOf(it.packageName, it.decl.container, it.decl.name).filter { part -> part.isNotEmpty() }.joinToString(".") == qualified } == 1 &&
            decl.params.isEmpty() && decl.kind in NAMED
        return if (alone) qualified else "$path:${decl.declStart}"
    }

    /** The lines of the declaration as `symbol` shows them: the documentation, the annotations, the body. */
    fun code(text: String): String = text.removePrefix("﻿").replace("\r\n", "\n").lines().subList(decl.start - 1, decl.end).joinToString("\n")

    /** A parameter of a primary constructor or a record has no lines of its own: the tool refuses to edit it alone. */
    fun isHeaderParameter(): Boolean = decl.kind == "property" && parent != null && (parent.bodyOpen < 0 || decl.startOffset < parent.bodyOpen)

    private companion object {
        val NAMED = setOf("class", "interface", "object", "enum", "annotation", "fun")
    }
}

/** A git repository holding a write fixture in one [Variant], with the services of the `edit` tool over it, as a daemon would run them. */
class WriteHarness(
    val fixture: String,
    val variant: Variant = Variant.LF,
    config: WriteConfig = WriteConfig(),
    extra: Map<String, String> = emptyMap(),
    overlayCheckMs: Long = 0,
) {
    val repo: Path = TestRepos.fixtureRepo(fixture, transformed(fixture, variant) + extra)
    val home: Path = TestRepos.tmpDir("write-home")
    private val registry = Registry(Config(home, 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = overlayCheckMs), JobQueue(CoroutineScope(Dispatchers.Default)))
    val journal = WriteJournal(home.resolve("writes.jsonl"))
    val service = WriteService(registry, WritePolicy(config), journal)
    val root: String = repo.toString()

    /** What `symbol` shows for [name]: its hash and its lines as the file has them (without the line ends). */
    class Read(val hash: String, val code: String)

    fun read(name: String): Read {
        val answer = runBlocking { registry.query(root) { view -> SymbolQuery.run(view, SymbolQuery.Args(name)) } }
        val head = answer.lineSequence().first()
        return Read(Regex("hash=([0-9a-f]{10})").find(head)?.groupValues?.get(1) ?: error("no hash in: $answer"), answer.substringAfter('\n'))
    }

    fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    fun find(query: String): String = runBlocking { registry.query(root) { view -> codeloupe.query.FindQuery.run(view, codeloupe.query.FindQuery.Args(query)) } }

    fun registry(): Registry = registry

    /** Source files of the repository, relative, with `/`. */
    fun sources(): List<String> = Files.walk(repo).use { paths ->
        paths.filter { Files.isRegularFile(it) && Languages.languageOf(it.toString()) != null && !it.toString().contains("${Path.of(".git")}") }
            .map { it.relativeTo(repo).toString().replace('\\', '/') }.sorted().toList()
    }

    fun bytes(path: String): ByteArray = Files.readAllBytes(repo.resolve(path))

    fun text(path: String): String = Files.readString(repo.resolve(path))

    fun snapshot(): Map<String, List<Byte>> = sources().associateWith { bytes(it).toList() }

    /** The declarations of every source file, read from the disk as it is now. */
    fun declarations(): List<Located> = sources().flatMap { path ->
        val facts = Extraction.extract(path, text(path))
        facts.decls.map { Located(path, facts.packageName, it, facts.decls.getOrNull(it.parent)) }
    }

    /** Compile errors of the repository as it is now. */
    fun compileErrors(): List<String> = Compile.kotlin(repo) + Compile.java(repo)

    private companion object {
        fun transformed(fixture: String, variant: Variant): Map<String, String> {
            val root = TestRepos.FIXTURES.resolve(fixture)
            return Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) }.toList().associate { it.relativeTo(root).toString().replace('\\', '/') to variant.transform(Files.readString(it)) }
            }
        }
    }
}
