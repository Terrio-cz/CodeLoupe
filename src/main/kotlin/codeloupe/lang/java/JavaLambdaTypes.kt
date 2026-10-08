package codeloupe.lang.java

import codeloupe.lang.TypeSpec
import codeloupe.lang.kotlin.childList
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable

/**
 * Types the target gives a lambda's single parameter: the declared type of the variable it initializes
 * (`Consumer<Item> c = i -> …`), or the standard library call it is passed to (`xs.forEach(x -> …)`, `xs.stream().filter(x -> …)`
 * - an element of `xs`). A lambda passed to any other call has the parameter type that call declares, which only the
 * query side can read.
 */
internal class JavaLambdaTypes(private val types: JavaLocalTypes) {
    /** Spec of the single parameter of an implicitly typed lambda; "" when the target is not a known one. */
    fun parameter(lambda: PsiLambdaExpression): String = declared(lambda) ?: passed(lambda) ?: ""

    private fun declared(lambda: PsiLambdaExpression): String? {
        val variable = lambda.parent as? PsiVariable ?: return null
        val type = variable.childList().filterIsInstance<PsiTypeElement>().firstOrNull()?.takeUnless(types::isVar) ?: return null
        return functionParameter(types.typeText(type))
    }

    private fun passed(lambda: PsiLambdaExpression): String? {
        val call = lambda.parent?.takeIf { it is PsiExpressionList }?.parent as? PsiMethodCallExpression ?: return null
        val callee = call.methodExpression
        val receiver: PsiExpression = callee.qualifierExpression ?: return null
        return if (callee.referenceName in ELEMENT) TypeSpec.element(types.ofExpression(receiver)) else null
    }

    // `Consumer<? super Item>` -> `Item`: the parameter of the single-argument functional interfaces of the JDK.
    private fun functionParameter(type: String): String? {
        if (type.substringBefore('<').substringAfterLast('.') !in FUNCTIONAL || '<' !in type) return null
        val arguments = type.substringAfter('<').substringBeforeLast('>')
        var depth = 0
        val end = arguments.indexOfFirst { c ->
            if (c == '<') depth++ else if (c == '>') depth--
            c == ',' && depth == 0
        }
        val first = (if (end < 0) arguments else arguments.substring(0, end)).trim()
        return first.removePrefix("? super ").removePrefix("? extends ").takeIf { it != "?" }
    }

    private companion object {
        val FUNCTIONAL = setOf("Consumer", "Predicate", "Function", "UnaryOperator", "ToIntFunction", "ToLongFunction", "ToDoubleFunction")
        val ELEMENT = setOf(
            "forEach", "forEachOrdered", "removeIf", "anyMatch", "allMatch", "noneMatch", "filter", "map", "flatMap", "mapToInt",
            "mapToLong", "mapToDouble", "mapToObj", "peek", "takeWhile", "dropWhile", "ifPresent", "ifPresentOrElse",
        )
    }
}
