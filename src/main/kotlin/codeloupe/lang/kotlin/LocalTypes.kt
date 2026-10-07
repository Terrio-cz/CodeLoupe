package codeloupe.lang.kotlin

import codeloupe.lang.JsText
import codeloupe.lang.TypeSpec
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Type specs ([TypeSpec]) of bindings, receivers and undeclared types: what the syntax tells directly — a declared
 * type, `Type(...)`, a string — else the position of the reference whose declaration has the type, which only the
 * query side can resolve.
 */
internal class LocalTypes(private val source: Source, private val scopes: LocalScopes) {
    fun of(declared: KtTypeReference?, initializer: KtExpression?): String =
        declared?.let { JsText.squash(source.of(it)) } ?: initializer?.let(::ofExpression) ?: ""

    /** A property's type when it declares none: its initializer, or the value of `by lazy { … }`. */
    fun ofProperty(property: KtProperty): String? {
        property.initializer?.let { return ofExpression(it).ifEmpty { null } }
        val lazy = property.delegateExpression as? KtCallExpression ?: return null
        if (lazy.calleeExpression?.text != "lazy") return null
        val value = lazy.lambdaArguments.singleOrNull()?.getLambdaExpression()?.bodyExpression?.statements?.lastOrNull()
        return value?.let(::ofExpression)?.ifEmpty { null }
    }

    /** `for (x in items)`: the element type of `items`. */
    fun elementOf(range: KtExpression?): String = range?.let(::ofExpression)?.let(TypeSpec::element) ?: ""

    /** The spec of a receiver expression; null for a name that is not bound locally (the query side looks it up). */
    fun ofReceiver(receiver: KtExpression): String? {
        val inner = unwrapped(receiver)
        if (inner is KtNameReferenceExpression) return scopes.typeOf(JsText.bare(source.of(inner)))
        return ofExpression(receiver).ifEmpty { null }
    }

    fun ofExpression(expression: KtExpression): String = when (val e = unwrapped(expression)) {
        is KtBinaryExpressionWithTypeRHS -> e.right?.let { JsText.squash(source.of(it)) } ?: ""
        is KtStringTemplateExpression -> "String"
        is KtNameReferenceExpression -> scopes.typeOf(JsText.bare(source.of(e))) ?: at(e)
        is KtCallExpression -> constructed(e) ?: e.calleeExpression?.let(::at) ?: ""
        is KtQualifiedExpression -> qualifiedConstructor(e) ?: when (val selector = e.selectorExpression) {
            is KtCallExpression -> library(selector, e.receiverExpression) ?: selector.calleeExpression?.let(::at) ?: ""
            is KtNameReferenceExpression -> at(selector)
            else -> ""
        }
        else -> ""
    }

    // `(x)`, `x!!`, `x ?: fallback` have the type of `x` as far as members go.
    private fun unwrapped(expression: KtExpression): KtExpression = when (expression) {
        is KtParenthesizedExpression -> expression.expression?.let(::unwrapped) ?: expression
        is KtPostfixExpression -> if (expression.operationToken == KtTokens.EXCLEXCL) expression.baseExpression?.let(::unwrapped) ?: expression else expression
        is KtBinaryExpression -> if (expression.operationToken == KtTokens.ELVIS) expression.left?.let(::unwrapped) ?: expression else expression
        else -> expression
    }

    // Standard library calls that hand back the receiver, a like collection or one of its elements — unless the
    // index knows a declaration of that name for the receiver, hence `@call|fallback`.
    private fun library(call: KtCallExpression, receiver: KtExpression): String? {
        val callee = call.calleeExpression ?: return null
        val fallback = when (callee.text) {
            in SAME -> ofExpression(receiver)
            in ELEMENT -> TypeSpec.element(ofExpression(receiver))
            else -> return null
        }
        return at(callee) + (if (fallback.isEmpty()) "" else "${TypeSpec.OR}$fallback")
    }

    private fun at(expression: KtExpression): String {
        if (expression !is KtNameReferenceExpression) return ""
        val offset = expression.textRange.startOffset
        return TypeSpec.position(source.lines.line(offset), source.lines.column(offset) + 1)
    }

    // `pkg.Type(...)`, `Outer.Inner(...)`
    private fun qualifiedConstructor(expression: KtQualifiedExpression): String? {
        if (expression !is KtDotQualifiedExpression) return null
        val type = (expression.selectorExpression as? KtCallExpression)?.let(::constructed) ?: return null
        val qualifier = source.of(expression.receiverExpression)
        return if (QUALIFIER.matches(qualifier)) JsText.squash(qualifier) + "." + type else null
    }

    private fun constructed(call: KtCallExpression): String? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        return JsText.bare(source.of(callee)).takeIf { it.firstOrNull()?.isUpperCase() == true }
    }

    private companion object {
        val SAME = setOf(
            "apply", "also", "takeIf", "takeUnless", "filter", "filterNot", "filterNotNull", "sortedBy", "sortedByDescending",
            "sorted", "sortedWith", "distinct", "distinctBy", "take", "drop", "takeLast", "dropLast", "reversed", "onEach",
        )
        val ELEMENT = setOf("first", "firstOrNull", "last", "lastOrNull", "single", "singleOrNull", "find", "findLast", "get", "getOrNull", "elementAt", "random")

        /** `Outer.Inner`, `pkg.Type`, `java.io` (a package before a capitalised constructor). */
        val QUALIFIER = Regex("""[A-Za-z_][\w.]*""")
    }
}
