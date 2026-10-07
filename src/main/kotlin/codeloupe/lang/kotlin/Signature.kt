package codeloupe.lang.kotlin

import codeloupe.lang.JsText
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPropertyDelegate

/** Header text up to the body: what a reader needs to know a declaration without its implementation. */
internal object Signature {
    private const val MAX = 300

    fun of(element: PsiElement, span: Span, modifiers: Modifiers, source: Source): String {
        val start = modifiers.end ?: span.start
        val end = element.childList().firstOrNull(::isBody)?.textRange?.startOffset ?: span.end
        val words = modifiers.words
        val prefix = if (words.isEmpty()) "" else words.joinToString(" ") + " "
        return JsText.squash(prefix + source.text.substring(start, maxOf(start, end))).take(MAX)
    }

    fun ofWhole(span: Span, source: Source): String = JsText.squash(source.of(span)).take(MAX)

    private fun isBody(child: PsiElement): Boolean =
        child is KtClassBody || child is KtBlockExpression || child is KtPropertyAccessor || child is KtPropertyDelegate ||
            child.node.elementType == KtTokens.EQ
}
