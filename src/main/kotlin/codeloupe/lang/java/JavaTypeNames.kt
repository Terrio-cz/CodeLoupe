package codeloupe.lang.java

import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement

/** Names of a type reference as written, without type arguments. */
internal object JavaTypeNames {
    /** `a.b.C<X>` -> `a.b.C`. */
    fun dotted(reference: PsiJavaCodeReferenceElement): String {
        val qualifier = reference.qualifier as? PsiJavaCodeReferenceElement
        return (qualifier?.let { dotted(it) + "." } ?: "") + (reference.referenceName ?: "")
    }
}
