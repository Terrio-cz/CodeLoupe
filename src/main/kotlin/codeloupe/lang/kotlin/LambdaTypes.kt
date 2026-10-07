package codeloupe.lang.kotlin

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Types the standard library gives a lambda by the call it is passed to: `it` of `x.let {}` is `x`, of
 * `xs.filter {}` an element of `xs`; `x.apply {}`, `x.run {}`, `with(x) {}` make `x` the implicit receiver.
 */
internal class LambdaTypes(private val types: LocalTypes) {
    /** Spec of the lambda's single (or, for `…Indexed`, second) parameter; "" when the call is not a known one. */
    fun parameter(literal: KtFunctionLiteral): String {
        val (name, receiver) = call(literal) ?: return ""
        if (receiver == null) return ""
        return when (name.text) {
            in SELF -> types.ofExpression(receiver)
            in ELEMENT, in INDEXED -> types.elementOfSpec(types.ofExpression(receiver))
            else -> ""
        }
    }

    fun isIndexed(literal: KtFunctionLiteral): Boolean = call(literal)?.first?.text in INDEXED

    /** Spec of the implicit receiver the lambda brings, null when it brings none we know of. */
    fun receiver(literal: KtFunctionLiteral): String? {
        val (callee, receiver) = call(literal) ?: return null
        return when {
            callee.text in RECEIVER && receiver != null -> types.ofExpression(receiver)
            callee.text == "with" -> (callee.parent as KtCallExpression).valueArguments.firstOrNull()?.getArgumentExpression()?.let(types::ofExpression) ?: ""
            else -> null
        }
    }

    /** Callee of the call the lambda is an argument of, and that call's receiver. */
    private fun call(literal: KtFunctionLiteral): Pair<KtExpression, KtExpression?>? {
        val lambda = literal.parent as? KtLambdaExpression ?: return null
        val call = callOf(lambda) ?: return null
        val callee = call.calleeExpression ?: return null
        val receiver = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call }?.receiverExpression
        return callee to receiver
    }

    private fun callOf(lambda: KtLambdaExpression): KtCallExpression? {
        val argument = lambda.parent as? KtValueArgument ?: return null
        val owner = argument.parent.let { if (it is KtValueArgumentList) it.parent else it }
        return owner as? KtCallExpression
    }

    private companion object {
        val SELF = setOf("let", "also", "takeIf", "takeUnless")
        val RECEIVER = setOf("apply", "run")
        val INDEXED = setOf("forEachIndexed", "mapIndexed", "mapIndexedNotNull", "filterIndexed")
        val ELEMENT = setOf(
            "forEach", "onEach", "map", "mapNotNull", "flatMap", "filter", "filterNot", "any", "all", "none", "count",
            "first", "firstOrNull", "last", "lastOrNull", "find", "findLast", "single", "singleOrNull", "sortedBy",
            "sortedByDescending", "groupBy", "associateBy", "associateWith", "associate", "sumOf", "maxByOrNull", "minByOrNull",
            "maxOf", "minOf", "partition", "takeWhile", "dropWhile", "distinctBy", "indexOfFirst", "indexOfLast",
        )
    }
}
