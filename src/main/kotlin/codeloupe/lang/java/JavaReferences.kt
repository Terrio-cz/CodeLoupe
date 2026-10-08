package codeloupe.lang.java

import codeloupe.lang.JsText
import codeloupe.lang.RefFact
import codeloupe.lang.kotlin.LocalScopes
import codeloupe.lang.kotlin.Reference
import codeloupe.lang.kotlin.Source
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNameValuePair
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiThisExpression

/**
 * Classifies a name use: `call` (a method call or `new Type(...)`), `nav` (selector after `.`), `type` (with its
 * qualifier as receiver), `callable_ref` (`Recv::name`), `named_arg` (an annotation element, with the annotation's name
 * as receiver), otherwise `name`. Names bound inside code carry their local type (see [LocalScopes]), prefixed with
 * [RefFact.BEYOND_CLASS] when a class body lies between. Java keeps methods and variables apart, so a call never
 * meets a binding.
 */
internal class JavaReferences(private val source: Source, private val scopes: LocalScopes, private val types: JavaLocalTypes) {
    /** A variable, field or class name used as an expression. */
    fun name(expression: PsiReferenceExpression): Reference? {
        val identifier = expression.referenceNameElement as? PsiIdentifier ?: return null
        val name = identifier.text
        val offset = identifier.textRange.startOffset
        val qualifier = expression.qualifierExpression ?: return unqualified(name, offset, NAME, null)
        return qualified(name, offset, NAV, qualifier, null)
    }

    /** The callee of a method call; [args] is the number of arguments. */
    fun call(callee: PsiReferenceExpression, args: Int): Reference? {
        val identifier = callee.referenceNameElement as? PsiIdentifier ?: return null
        val offset = identifier.textRange.startOffset
        val qualifier = callee.qualifierExpression ?: return unqualified(identifier.text, offset, CALL, args)
        return qualified(identifier.text, offset, CALL, qualifier, args)
    }

    /** One segment of a type name: the class created by `new` ([kind] call), a type in a declaration or `extends`. */
    fun segment(reference: PsiJavaCodeReferenceElement, kind: String, args: Int?): Reference? {
        val identifier = reference.referenceNameElement ?: return null
        val recv = (reference.qualifier as? PsiElement)?.let { JsText.squash(source.of(it)) }
        return Reference(identifier.text, identifier.textRange.startOffset, kind, recv, args = args)
    }

    /** `Type::name` and `expr::name`; `Type::new` is the qualifier alone. */
    fun methodReference(expression: PsiMethodReferenceExpression): Reference? {
        val identifier = expression.referenceNameElement as? PsiIdentifier ?: return null
        val offset = identifier.textRange.startOffset
        val qualifier = expression.qualifierExpression
        if (qualifier != null) return qualified(identifier.text, offset, CALLABLE_REF, qualifier, null)
        val type = expression.qualifierType?.let { JsText.squash(source.of(it)).take(MAX_RECEIVER) }
        return Reference(identifier.text, offset, CALLABLE_REF, type)
    }

    /** `@Marker(name = value)`: the element name, with the annotation as receiver. */
    fun annotationElement(pair: PsiNameValuePair): Reference? {
        val identifier = pair.nameIdentifier ?: return null
        val annotation = generateSequence(pair.parent) { it.parent }.firstOrNull { it is PsiAnnotation } as? PsiAnnotation
        return Reference(identifier.text, identifier.textRange.startOffset, NAMED_ARG, annotation?.nameReferenceElement?.referenceName)
    }

    // A name bound in code is that binding; a method name never is.
    private fun unqualified(name: String, offset: Int, kind: String, args: Int?): Reference {
        val binding = if (kind == CALL) null else scopes.lookup(name)
        val bind = binding?.let { if (it.beyondClass) RefFact.BEYOND_CLASS + it.type else it.type }
        return Reference(name, offset, kind, null, bind = bind, args = args)
    }

    private fun qualified(name: String, offset: Int, kind: String, receiver: PsiExpression, args: Int?): Reference =
        Reference(name, offset, kind, receiver(receiver), recvType = localType(receiver), args = args)

    // A plain `this` is the enclosing class, which the query side knows.
    private fun localType(receiver: PsiExpression): String? =
        if (receiver is PsiThisExpression) scopes.thisReceiver.takeIf { receiver.qualifier == null } else types.ofReceiver(receiver)

    private fun receiver(expression: PsiExpression): String {
        if (expression is PsiThisExpression) expression.qualifier?.let { return "this@" + JavaTypeNames.dotted(it) }
        return JsText.squash(source.of(expression)).take(MAX_RECEIVER)
    }

    private companion object {
        const val CALL = "call"
        const val NAV = "nav"
        const val CALLABLE_REF = "callable_ref"
        const val NAMED_ARG = "named_arg"
        const val NAME = "name"
        const val MAX_RECEIVER = 60
    }
}
