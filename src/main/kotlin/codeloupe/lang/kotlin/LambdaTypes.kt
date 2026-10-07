package codeloupe.lang.kotlin

import codeloupe.lang.TypeSpec
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Types the standard library gives a lambda by the call it is passed to: `it` of `x.let {}` is `x`, of
 * `xs.filter {}` an element of `xs`; `x.apply {}`, `x.run {}`, `with(x) {}` make `x` the implicit receiver. A lambda
 * passed to any other call may have a receiver of its own (DSL builders): its type is unknown.
 */
internal class LambdaTypes(private val types: LocalTypes) {
    /** Spec of the lambda's single (or, for `…Indexed`, second) parameter; "" when the call is not a known one. */
    fun parameter(literal: KtFunctionLiteral): String {
        val (callee, receiver) = call(literal) ?: return ""
        if (receiver == null) return ""
        return when (callee.text) {
            in SELF -> types.ofExpression(receiver)
            in ELEMENT, in INDEXED -> TypeSpec.element(types.ofExpression(receiver))
            else -> ""
        }
    }

    fun isIndexed(literal: KtFunctionLiteral): Boolean = call(literal)?.first?.text in INDEXED

    /** `x.apply {}`, `x.run {}` and `with(x) {}` lambdas take no parameter, so there is no `it`. */
    fun hasNoParameter(literal: KtFunctionLiteral): Boolean =
        call(literal)?.let { (callee, receiver) -> (callee.text in RECEIVER && receiver != null) || callee.text == "with" } == true

    /** Spec of the implicit receiver the lambda brings, "" when it may bring one of unknown type, null when none. */
    fun receiver(literal: KtFunctionLiteral): String? {
        val (callee, receiver) = call(literal) ?: return null
        val name = callee.text
        return when {
            name in RECEIVER && receiver != null -> types.ofExpression(receiver)
            name == "with" -> (callee.parent as KtCallExpression).valueArguments.firstOrNull()?.getArgumentExpression()?.let(types::ofExpression) ?: ""
            name in RECEIVER || name in RECEIVERLESS || name in SELF || name in ELEMENT || name in INDEXED -> null
            else -> ""
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

        /** Standard library calls whose lambda has no receiver. */
        val RECEIVERLESS = setOf(
            "use", "repeat", "lazy", "require", "requireNotNull", "check", "checkNotNull", "getOrElse", "getOrPut", "compareBy",
            "sortedWith", "thenBy", "maxWith", "minWith", "fold", "reduce", "runCatching", "synchronized", "measureTimeMillis",
            "assertFailsWith", "assertThrows", "zip", "windowed", "chunked", "error",
        )
    }
}
