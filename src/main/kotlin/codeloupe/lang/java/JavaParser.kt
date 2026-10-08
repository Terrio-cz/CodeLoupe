package codeloupe.lang.java

import codeloupe.lang.PsiEnvironment
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile

/** The Java parser of the Kotlin compiler's PSI: syntax only, at the highest language level. */
internal class JavaParser {
    fun parse(text: String): PsiJavaFile =
        PsiEnvironment.factory.createFileFromText("File.java", JavaLanguage.INSTANCE, PsiEnvironment.psiSafe(text)) as PsiJavaFile
}
