package codeloupe.lang.kotlin

import codeloupe.lang.PsiEnvironment
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile

/** The Kotlin compiler's own parser. */
internal class KotlinParser {
    fun parse(path: String, text: String): KtFile {
        val name = if (path.endsWith(".kts", ignoreCase = true)) "file.kts" else "file.kt"
        return PsiEnvironment.factory.createFileFromText(name, KotlinLanguage.INSTANCE, PsiEnvironment.psiSafe(text)) as KtFile
    }
}
