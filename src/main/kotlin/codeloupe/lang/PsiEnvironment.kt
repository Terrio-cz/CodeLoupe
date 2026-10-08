package codeloupe.lang

import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * The compiler's parser environment, used for syntax only: no classpath, no resolution. One per process, shared by the
 * Kotlin and the Java parser (the compiler registers both languages), created on first use.
 */
internal object PsiEnvironment {
    val factory: PsiFileFactory by lazy {
        setIdeaIoUseFallback()
        val config = CompilerConfiguration()
        config.extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        // Lives as long as the process: only the short-lived build worker parses.
        val environment = KotlinCoreEnvironment.createForProduction(
            Disposer.newDisposable("codeloupe-parser"), config, EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        PsiFileFactory.getInstance(environment.project)
    }

    private val BOM = Char(0xFEFF)

    /**
     * PSI wants `\n` line ends and no BOM. Spaces in their place keep every offset equal to the original text,
     * so all text the index stores is cut from the original.
     */
    fun psiSafe(text: String): String {
        if (text.indexOf('\r') < 0 && !text.startsWith(BOM)) return text
        val chars = text.toCharArray()
        for (i in chars.indices) if (chars[i] == '\r') chars[i] = ' '
        if (chars[0] == BOM) chars[0] = ' '
        return String(chars)
    }
}
