package codeloupe.golden

import codeloupe.lang.kotlin.KotlinParser
import codeloupe.query.View
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementVisitor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective

/**
 * What `rg -w name` finds in code, computed independently of the index: identifier tokens outside comments,
 * strings, `package`/`import` lines and declaration names (a declaration is not a use of itself).
 */
object IdentifierScan {
    data class Position(val path: String, val line: Int, val col: Int)

    fun positions(view: View, names: Set<String>): Map<String, List<Position>> {
        val parser = KotlinParser()
        val found = names.associateWith { ArrayList<Position>() }
        for (path in view.filesBySuffix(".kt") + view.filesBySuffix(".kts")) {
            val text = view.file(path)?.content ?: continue
            if (names.none { it in text }) continue
            val lineStarts = lineStarts(text)
            parser.parse(path, text).accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element.node.elementType == KtTokens.IDENTIFIER) {
                        val name = element.text.removeSurrounding("`")
                        if (name in names && isUse(element)) found.getValue(name) += position(path, element.textRange.startOffset, lineStarts)
                    }
                    super.visitElement(element)
                }
            })
        }
        return found
    }

    private fun isUse(identifier: PsiElement): Boolean {
        val parent = identifier.parent
        if (parent is PsiNameIdentifierOwner && parent.nameIdentifier == identifier) return false
        return generateSequence(identifier) { it.parent }.none { it is PsiComment || it is KtImportDirective || it is KtPackageDirective }
    }

    private fun lineStarts(text: String): IntArray =
        (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()

    private fun position(path: String, offset: Int, starts: IntArray): Position {
        val index = starts.indexOfLast { it <= offset }
        return Position(path, index + 1, offset - starts[index] + 1)
    }
}
