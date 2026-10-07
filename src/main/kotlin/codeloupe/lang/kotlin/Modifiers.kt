package codeloupe.lang.kotlin

import codeloupe.lang.JsText
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtAnnotation
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtModifierList

/**
 * The modifiers of a declaration as the index records them: annotations (whitespace squashed) and modifier
 * keywords in source order. [end] is where they stop, so the signature can follow them.
 */
internal data class Modifiers(val texts: List<String>, val end: Int?) {
    val words: List<String> get() = texts.filter { !it.startsWith("@") }

    companion object {
        val NONE = Modifiers(emptyList(), null)

        // `companion` and the `fun` of `fun interface` belong to the declaration keyword, not to its modifiers.
        private val KEYWORDS = setOf(
            "enum", "sealed", "annotation", "data", "inner", "value",
            "tailrec", "operator", "infix", "inline", "external", "suspend",
            "const", "public", "private", "protected", "internal", "abstract", "final", "open",
            "override", "lateinit", "vararg", "noinline", "crossinline", "expect", "actual",
        )

        fun of(list: KtModifierList?, source: Source): Modifiers {
            if (list == null) return NONE
            val texts = ArrayList<String>()
            var end: Int? = null
            val comments = ArrayList<PsiElement>()
            for (child in list.childList()) {
                val text = when {
                    child is PsiComment -> null.also { comments += child }
                    child is KtAnnotationEntry || child is KtAnnotation -> JsText.squash(source.of(child))
                    child.firstChild == null && child.text in KEYWORDS -> child.text
                    else -> null
                } ?: continue
                // A comment between two modifiers is recorded as one of them.
                comments.forEach { texts += source.of(it) }
                comments.clear()
                texts += text
                end = child.textRange.endOffset
            }
            return Modifiers(texts, end)
        }
    }
}
