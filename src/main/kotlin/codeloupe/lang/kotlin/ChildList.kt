package codeloupe.lang.kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement

/** Direct children including whitespace, comments and tokens, in source order. */
internal fun PsiElement.childList(): List<PsiElement> = generateSequence(firstChild) { it.nextSibling }.toList()
