package codeloupe.lang.kotlin

import codeloupe.lang.JsText
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtContainerNode
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName

/**
 * Classifies a name use: `call` (callee of a call or an infix call), `nav` (selector after `.`, `?.` or `Recv::`),
 * `type`, `callable_ref` (`::name`), `named_arg`, otherwise `name`. Calls and navigations keep their receiver text.
 */
internal class References(private val source: Source) {
    fun of(expression: KtSimpleNameExpression): Reference? = when (expression) {
        is KtLabelReferenceExpression -> label(expression)
        is KtOperationReferenceExpression -> infixCall(expression)
        is KtNameReferenceExpression -> name(expression)
        else -> null
    }

    /** A parameter name the index counts as a use (catch and setter parameters). */
    fun parameterName(identifier: PsiElement): Reference =
        Reference(JsText.bare(source.of(identifier)), identifier.textRange.startOffset, NAME, null)

    private fun name(expression: KtNameReferenceExpression): Reference? {
        if (expression.getReferencedNameElementType() != KtTokens.IDENTIFIER) return null
        val parent = expression.parent
        val (kind, recv) = when {
            parent is KtUserType -> TYPE to null
            parent is KtCallableReferenceExpression && parent.callableReference == expression ->
                parent.receiverExpression?.let { NAV to receiver(it) } ?: (CALLABLE_REF to null)
            parent is KtCallExpression && parent.calleeExpression == expression -> CALL to qualifierOf(parent)
            parent is KtQualifiedExpression && parent.selectorExpression == expression -> NAV to receiver(parent.receiverExpression)
            parent is KtValueArgumentName -> NAMED_ARG to null
            else -> NAME to null
        }
        return Reference(JsText.bare(source.of(expression)), expression.textRange.startOffset, kind, recv)
    }

    private fun infixCall(expression: KtOperationReferenceExpression): Reference? {
        if (expression.getReferencedNameElementType() != KtTokens.IDENTIFIER) return null
        val binary = expression.parent as? KtBinaryExpression ?: return null
        return Reference(JsText.bare(source.of(expression)), expression.textRange.startOffset, CALL, binary.left?.let(::receiver))
    }

    // `return@forEach`, `this@Outer` use a label; `loop@ for` defines one.
    private fun label(expression: KtLabelReferenceExpression): Reference? {
        val owner = generateSequence(expression.parent) { it.parent }.firstOrNull { it !is KtContainerNode }
        if (owner is KtLabeledExpression) return null
        val nameElement = expression.getReferencedNameElement()
        val raw = source.of(nameElement).removePrefix("@")
        return Reference(JsText.bare(raw), nameElement.textRange.endOffset - raw.length, NAME, null)
    }

    private fun qualifierOf(call: KtCallExpression): String? {
        val qualified = call.parent as? KtQualifiedExpression ?: return null
        return if (qualified.selectorExpression == call) receiver(qualified.receiverExpression) else null
    }

    /** `Type::class` is recorded as navigating to a member named `class`. */
    fun classLiteral(literal: KtClassLiteralExpression, keyword: PsiElement): Reference? {
        val receiver = literal.receiverExpression ?: return null
        return Reference("class", keyword.textRange.startOffset, NAV, receiver(receiver))
    }

    private fun receiver(expression: KtExpression): String = JsText.squash(source.of(expression)).take(MAX_RECEIVER)

    private companion object {
        const val CALL = "call"
        const val NAV = "nav"
        const val TYPE = "type"
        const val CALLABLE_REF = "callable_ref"
        const val NAMED_ARG = "named_arg"
        const val NAME = "name"
        const val MAX_RECEIVER = 60
    }
}
