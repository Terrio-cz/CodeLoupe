package codeloupe.write

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/** The real compilers, in process: what the write tests ask whether the edited sources still compile. */
object Compile {
    /** The error lines of `kotlinc` over every `.kt` under [root]; empty when it compiles. */
    fun kotlin(root: Path): List<String> {
        val files = sources(root, ".kt")
        if (files.isEmpty()) return emptyList()
        val out = Files.createTempDirectory("codeloupe-kotlinc-")
        val log = ByteArrayOutputStream()
        val stdlib = Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val exit = K2JVMCompiler().exec(PrintStream(log), "-d", out.toString(), "-no-stdlib", "-no-reflect", "-cp", stdlib, "-jdk-home", System.getProperty("java.home"), *files.toTypedArray())
        return if (exit == ExitCode.OK) emptyList() else log.toString().lines().filter { "error:" in it }.ifEmpty { listOf(log.toString().take(2000)) }
    }

    /** The errors of `javac` over every `.java` under [root]; empty when it compiles. */
    fun java(root: Path): List<String> {
        val files = sources(root, ".java")
        if (files.isEmpty()) return emptyList()
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("this JVM has no Java compiler")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val out = Files.createTempDirectory("codeloupe-javac-")
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
            val units = manager.getJavaFileObjectsFromStrings(files)
            compiler.getTask(null, manager, diagnostics, listOf("-d", out.toString(), "-proc:none"), null, units).call()
        }
        return diagnostics.diagnostics.filter { it.kind == javax.tools.Diagnostic.Kind.ERROR }.map { "${it.source?.name}:${it.lineNumber}: ${it.getMessage(null)}" }
    }

    private fun sources(root: Path, suffix: String): List<String> =
        Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(suffix) && !it.toString().contains("${Path.of(".git")}") }.map { it.toString() }.toList() }
}
