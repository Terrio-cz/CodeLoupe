package codeloupe.lang.java

import codeloupe.lang.kotlin.Span
import codeloupe.lang.kotlin.childList
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace

/**
 * Offsets of a Java element's own code: its doc comment is a child of the declaration in PSI, and an absent modifier
 * list is an empty child, neither belongs to the span.
 */
internal object JavaSpan {
    fun of(element: PsiElement): Span {
        val children = element.childList().filter { it !is PsiWhiteSpace && it !is PsiComment && it.textLength > 0 }
        val first = children.firstOrNull() ?: return Span(element.textRange.startOffset, element.textRange.endOffset)
        return Span(first.textRange.startOffset, children.last().textRange.endOffset)
    }
}
