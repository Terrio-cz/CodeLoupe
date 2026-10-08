package codeloupe.golden

import codeloupe.lang.java.JavaParser
import codeloupe.query.View
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiPackageStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementVisitor

/** [IdentifierScan] for Java files: identifier tokens outside comments, `package`/`import` and declaration names. */
object JavaIdentifierScan {
    fun positions(view: View, names: Set<String>): Map<String, List<IdentifierScan.Position>> {
        val parser = JavaParser()
        val found = names.associateWith { ArrayList<IdentifierScan.Position>() }
        for (path in view.filesBySuffix(".java")) {
            val text = view.file(path)?.content ?: continue
            if (names.none { it in text }) continue
            val lineStarts = (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()
            parser.parse(text).accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is PsiIdentifier && element.text in names && isUse(element)) {
                        val offset = element.textRange.startOffset
                        val row = lineStarts.indexOfLast { it <= offset }
                        found.getValue(element.text) += IdentifierScan.Position(path, row + 1, offset - lineStarts[row] + 1)
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
        return generateSequence(identifier) { it.parent }.none { it is PsiComment || it is PsiImportStatementBase || it is PsiPackageStatement }
    }
}
