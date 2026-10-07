package codeloupe.lang.kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtEnumEntry

/** Offsets of an element's own code: PSI binds neighbouring comments (KDoc too) into declarations; the index does not. */
internal data class Span(val start: Int, val end: Int) {
    companion object {
        fun of(element: PsiElement): Span {
            val children = element.childList()
            val first = children.firstOrNull { !it.isTrivia() }
                ?: return Span(element.textRange.startOffset, element.textRange.endOffset)
            val last = children.last { !it.isTrivia() && !(element is KtEnumEntry && it.isSeparator()) }
            return Span(first.textRange.startOffset, last.textRange.endOffset)
        }

        private fun PsiElement.isTrivia() = this is PsiWhiteSpace || this is PsiComment

        private fun PsiElement.isSeparator() = node.elementType == KtTokens.COMMA || node.elementType == KtTokens.SEMICOLON
    }
}
