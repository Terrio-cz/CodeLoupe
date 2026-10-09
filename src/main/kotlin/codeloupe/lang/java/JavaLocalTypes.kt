package codeloupe.lang.java

import codeloupe.lang.JsText
import codeloupe.lang.TypeSpec
import codeloupe.lang.kotlin.LocalScopes
import codeloupe.lang.kotlin.Source
import codeloupe.lang.kotlin.childList
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiParenthesizedExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCastExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement

/**
 * Type specs ([TypeSpec]) of Java bindings, receivers and `var` declarations: what the syntax tells directly - a
 * declared type, `new Type(...)`, a cast, a string - else the position of the reference whose declaration has the type,
 * which only the query side can resolve.
 */
internal class JavaLocalTypes(private val source: Source, private val scopes: LocalScopes) {
    /**
     * The declared type, or for `var` the type of the initializer; "" for an implicitly typed lambda parameter. The
     * dimensions written after the name of [owner] (`String s[]`) belong to the type.
     */
    fun of(declared: PsiTypeElement?, initializer: PsiExpression?, owner: PsiElement? = null): String = when {
        declared == null -> ""
        isVar(declared) -> initializer?.let(::ofExpression) ?: ""
        else -> typeText(declared) + dimensions(owner)
    }

    /** `[]` for each pair of brackets after the name: `int d[]`. */
    fun dimensions(owner: PsiElement?): String =
        "[]".repeat(owner?.childList()?.count { it is PsiJavaToken && it.tokenType == JavaTokenType.LBRACKET } ?: 0)

    fun isVar(type: PsiTypeElement) = type.textLength == VAR.length && source.of(type) == VAR

    /** Type text as written; a varargs `T...` is the array `T[]` it stands for. */
    fun typeText(type: PsiTypeElement): String {
        val text = JsText.squash(source.of(type))
        return if (text.endsWith(VARARGS)) text.removeSuffix(VARARGS).trimEnd() + "[]" else text
    }

    /** `for (var x : items)`: the element type of `items`. */
    fun elementOf(range: PsiExpression?): String = range?.let(::ofExpression)?.let(TypeSpec::element) ?: ""

    /** The spec of a receiver expression; a name not bound locally is typed by what it denotes (`@line:col`). */
    fun ofReceiver(receiver: PsiExpression): String? {
        val inner = unwrapped(receiver)
        if (inner is PsiReferenceExpression && inner.qualifierExpression == null) {
            return scopes.typeOf(inner.referenceName ?: return null) ?: at(inner.referenceNameElement).ifEmpty { null }
        }
        return ofExpression(receiver).ifEmpty { null }
    }

    fun ofExpression(expression: PsiExpression): String = when (val e = unwrapped(expression)) {
        is PsiTypeCastExpression -> e.castType?.let(::typeText) ?: ""
        is PsiLiteralExpression -> if ((e.firstChild as? PsiJavaToken)?.tokenType in STRINGS) "String" else ""
        is PsiReferenceExpression -> reference(e)
        is PsiNewExpression -> constructed(e) ?: ""
        is PsiMethodCallExpression -> call(e)
        else -> ""
    }

    /** `@line:col` of a name, "" for anything else. */
    fun at(identifier: PsiElement?): String {
        if (identifier == null) return ""
        val offset = identifier.textRange.startOffset
        return TypeSpec.position(source.lines.line(offset), source.lines.column(offset) + 1)
    }

    private fun reference(e: PsiReferenceExpression): String {
        val name = e.referenceName ?: return ""
        return if (e.qualifierExpression == null) scopes.typeOf(name) ?: at(e.referenceNameElement) else at(e.referenceNameElement)
    }

    private fun call(e: PsiMethodCallExpression): String {
        val callee = e.methodExpression
        val name = callee.referenceNameElement ?: return ""
        val receiver = callee.qualifierExpression
        val fallback = when {
            receiver == null -> null
            callee.referenceName in SAME -> ofExpression(receiver)
            callee.referenceName in ELEMENT -> TypeSpec.element(ofExpression(receiver))
            else -> null
        }
        return at(name) + if (fallback.isNullOrEmpty()) "" else "${TypeSpec.OR}$fallback"
    }

    private fun constructed(e: PsiNewExpression): String? {
        val reference = e.classReference ?: e.anonymousClass?.baseClassReference ?: return null
        return JavaTypeNames.dotted(reference)
    }

    // `(x)` has the type of `x`.
    private fun unwrapped(expression: PsiExpression): PsiExpression =
        if (expression is PsiParenthesizedExpression) expression.expression?.let(::unwrapped) ?: expression else expression

    private companion object {
        const val VAR = "var"
        const val VARARGS = "..."
        val STRINGS = setOf(JavaTokenType.STRING_LITERAL, JavaTokenType.TEXT_BLOCK_LITERAL)

        // Calls handing back the receiver, a like collection or one of its elements - unless the index knows a
        // declaration of that name for the receiver, hence `@call|fallback`.
        val SAME = setOf(
            "stream", "parallelStream", "filter", "sorted", "distinct", "limit", "skip", "takeWhile", "dropWhile", "reversed",
            "subList", "findFirst", "findAny", "min", "max", "toList", "iterator", "listIterator", "unmodifiableList", "copyOf",
        )
        val ELEMENT = setOf(
            "get", "getFirst", "getLast", "orElse", "orElseGet", "orElseThrow", "next", "peek", "poll", "pop", "element",
            "first", "last",
        )
    }
}
