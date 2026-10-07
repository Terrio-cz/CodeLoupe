package codeloupe.lang.kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement

/** The original text of a file: every stored string is cut from it, never from the PSI copy. */
internal class Source(val text: String) {
    val lines = LineIndex(text)

    fun of(element: PsiElement): String = text.substring(element.textRange.startOffset, element.textRange.endOffset)

    fun of(span: Span): String = text.substring(span.start, span.end)
}
