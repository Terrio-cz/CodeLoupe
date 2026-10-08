package codeloupe.lang.java

import codeloupe.lang.JsText
import codeloupe.lang.kotlin.Modifiers
import codeloupe.lang.kotlin.Source
import codeloupe.lang.kotlin.Span
import codeloupe.lang.kotlin.childList
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType

/** Header text up to the body or the closing `;`, after the modifier words: what a reader needs without the implementation. */
internal object JavaSignature {
    private const val MAX = 300

    fun of(element: PsiElement, span: Span, modifiers: Modifiers, source: Source): String {
        val start = modifiers.end ?: span.start
        val end = element.childList().firstOrNull(::isBody)?.textRange?.startOffset ?: span.end
        return prefixed(modifiers, source.text.substring(start, maxOf(start, end)))
    }

    /** A declaration without a header of its own (the second `int a, b;` declarator): modifier words and the given text. */
    fun prefixed(modifiers: Modifiers, text: String): String {
        val words = modifiers.words
        val prefix = if (words.isEmpty()) "" else words.joinToString(" ") + " "
        return JsText.squash(prefix + text).trimEnd(';').trimEnd().take(MAX)
    }

    private fun isBody(child: PsiElement): Boolean =
        child is PsiCodeBlock || (child is PsiJavaToken && (child.tokenType == JavaTokenType.LBRACE || child.tokenType == JavaTokenType.SEMICOLON))
}
