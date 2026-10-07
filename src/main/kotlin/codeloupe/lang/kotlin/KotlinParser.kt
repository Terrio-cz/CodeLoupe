package codeloupe.lang.kotlin

import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile

/** The Kotlin compiler's own parser, used for syntax only: no classpath, no resolution. */
internal class KotlinParser {
    private val factory: PsiFileFactory = run {
        setIdeaIoUseFallback()
        val config = CompilerConfiguration()
        config.extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        // Lives as long as the process: only the short-lived build worker parses.
        val environment = KotlinCoreEnvironment.createForProduction(
            Disposer.newDisposable("codeloupe-parser"), config, EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        PsiFileFactory.getInstance(environment.project)
    }

    fun parse(path: String, text: String): KtFile {
        val name = if (path.endsWith(".kts", ignoreCase = true)) "file.kts" else "file.kt"
        return factory.createFileFromText(name, KotlinLanguage.INSTANCE, psiSafe(text)) as KtFile
    }

    // PSI wants `\n` line ends and no BOM. Spaces in their place keep every offset equal to the original text,
    // so all text the index stores is cut from the original.
    private fun psiSafe(text: String): String {
        if (text.indexOf('\r') < 0 && !text.startsWith(BOM)) return text
        val chars = text.toCharArray()
        for (i in chars.indices) if (chars[i] == '\r') chars[i] = ' '
        if (chars[0] == BOM) chars[0] = ' '
        return String(chars)
    }

    private companion object {
        val BOM = Char(0xFEFF)
    }
}
