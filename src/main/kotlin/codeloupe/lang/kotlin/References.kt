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
 * `type`, `callable_ref` (`::name`), `named_arg`, otherwise `name`. Calls and navigations keep their receiver text;
 * names bound inside code carry their local type (see [LocalScopes]).
 */
internal class References(private val source: Source, private val scopes: LocalScopes, private val types: LocalTypes) {
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
        val name = JsText.bare(source.of(expression))
        val offset = expression.textRange.startOffset
        return when {
            parent is KtUserType -> Reference(name, offset, TYPE, null)
            parent is KtCallableReferenceExpression && parent.callableReference == expression ->
                parent.receiverExpression?.let { qualified(name, offset, NAV, it, null) }
                    ?: unqualified(name, offset, CALLABLE_REF, null)
            parent is KtCallExpression && parent.calleeExpression == expression -> {
                val args = argumentCount(parent)
                val qualifier = (parent.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == parent }
                if (qualifier != null) qualified(name, offset, CALL, qualifier.receiverExpression, args)
                else unqualified(name, offset, CALL, args)
            }
            parent is KtQualifiedExpression && parent.selectorExpression == expression -> qualified(name, offset, NAV, parent.receiverExpression, null)
            parent is KtValueArgumentName -> Reference(name, offset, NAMED_ARG, null)
            else -> unqualified(name, offset, NAME, null)
        }
    }

    // A name bound in code is that binding; any other may be a member of a lambda's implicit receiver.
    private fun unqualified(name: String, offset: Int, kind: String, args: Int?): Reference {
        val bind = scopes.typeOf(name)
        return Reference(name, offset, kind, null, bind = bind, recvType = if (bind == null) scopes.implicitReceiver else null, args = args)
    }

    private fun qualified(name: String, offset: Int, kind: String, receiver: KtExpression, args: Int?): Reference =
        Reference(name, offset, kind, receiver(receiver), recvType = localType(receiver), args = args)

    private fun localType(receiver: KtExpression): String? = types.ofReceiver(receiver)

    // `valueArguments` includes trailing lambdas.
    private fun argumentCount(call: KtCallExpression): Int =
        if (call.valueArguments.any { it.getSpreadElement() != null }) -1 else call.valueArguments.size

    private fun infixCall(expression: KtOperationReferenceExpression): Reference? {
        if (expression.getReferencedNameElementType() != KtTokens.IDENTIFIER) return null
        val binary = expression.parent as? KtBinaryExpression ?: return null
        val left = binary.left
        return Reference(JsText.bare(source.of(expression)), expression.textRange.startOffset, CALL, left?.let(::receiver), recvType = left?.let(::localType), args = 1)
    }

    // `return@forEach`, `this@Outer` use a label; `loop@ for` defines one.
    private fun label(expression: KtLabelReferenceExpression): Reference? {
        val owner = generateSequence(expression.parent) { it.parent }.firstOrNull { it !is KtContainerNode }
        if (owner is KtLabeledExpression) return null
        val nameElement = expression.getReferencedNameElement()
        val raw = source.of(nameElement).removePrefix("@")
        return Reference(JsText.bare(raw), nameElement.textRange.endOffset - raw.length, NAME, null)
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
