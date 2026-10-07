package codeloupe.lang.kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil

/** A declaration's range extends upwards over a `/** … */` that ends on the line above it (or on its first line). */
internal object Kdoc {
    fun line(element: PsiElement, span: Span, source: Source): Int? {
        var previous = element.containingFile.findElementAt(span.start)?.let(PsiTreeUtil::prevLeaf)
        while (previous != null && (previous is PsiWhiteSpace || previous.textLength == 0)) previous = PsiTreeUtil.prevLeaf(previous)
        val comment = PsiTreeUtil.getNonStrictParentOfType(previous, PsiComment::class.java) ?: return null
        if (!source.of(comment).startsWith("/**")) return null
        val lines = source.lines
        if (lines.row(comment.textRange.endOffset) < lines.row(span.start) - 1) return null
        return lines.line(comment.textRange.startOffset)
    }
}
