package codeloupe.lang.java

import codeloupe.lang.JsText
import codeloupe.lang.kotlin.Modifiers
import codeloupe.lang.kotlin.Source
import codeloupe.lang.kotlin.childList
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierList

/** Annotations (whitespace squashed) and modifier keywords of a Java declaration in source order, as [Modifiers]. */
internal object JavaModifiers {
    private val KEYWORDS = setOf(
        "public", "protected", "private", "static", "final", "abstract", "native", "synchronized", "transient", "volatile",
        "strictfp", "default", "sealed", "non-sealed",
    )

    fun of(list: PsiModifierList?, source: Source): Modifiers {
        if (list == null) return Modifiers.NONE
        val texts = ArrayList<String>()
        var end: Int? = null
        for (child in list.childList()) {
            val text = when {
                child is PsiAnnotation -> JsText.squash(source.of(child))
                child.text in KEYWORDS -> child.text
                else -> continue
            }
            texts += text
            end = child.textRange.endOffset
        }
        return Modifiers(texts, end)
    }
}
