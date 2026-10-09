package codeloupe.lang.java

import codeloupe.lang.JsText
import codeloupe.lang.ParamFact
import codeloupe.lang.kotlin.DeclShape
import codeloupe.lang.kotlin.Modifiers
import codeloupe.lang.kotlin.Source
import codeloupe.lang.kotlin.childList
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassInitializer
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiImplicitClass
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierList
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList
import org.jetbrains.kotlin.com.intellij.psi.PsiRecordComponent
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElement

/**
 * Reads kind, name, modifiers, parameters and types of each Java declaration form into the shapes the index shares with
 * Kotlin: a method is a `fun`, a field a `property`, a record component a constructor property, an enum constant an
 * `enum_entry`, an anonymous class a local `object`, an initializer block an `init`.
 */
internal class JavaShapes(private val source: Source, private val types: JavaLocalTypes, private val fileStem: String) {
    fun classLike(element: PsiClass): DeclShape {
        val kind = when {
            element.isAnnotationType -> "annotation"
            element.isInterface -> "interface"
            element.isEnum -> "enum"
            else -> "class"
        }
        val modifiers = modifiers(element.modifierList)
        if (element is PsiImplicitClass) return DeclShape(kind, fileStem, modifiers, sig = "class $fileStem")
        val sig = JavaSignature.of(element, JavaSpan.of(element), modifiers, source)
        return DeclShape(kind, element.nameIdentifier?.let(source::of) ?: "?", modifiers, supertypes = supertypes(element), sig = sig)
    }

    /** `new I() { … }`: a local object named [ANONYMOUS] with its supertype, so it counts as an implementation. */
    fun anonymous(element: PsiAnonymousClass): DeclShape {
        val base = element.baseClassReference
        return DeclShape("object", ANONYMOUS, Modifiers.NONE, supertypes = listOf(supertypeName(base)), sig = "new ${JavaTypeNames.dotted(base)}()")
    }

    /** [components] are the parameters of a compact record constructor, which names none. */
    fun method(element: PsiMethod, components: List<ParamFact>): DeclShape {
        val modifiers = modifiers(element.modifierList)
        val sig = JavaSignature.of(element, JavaSpan.of(element), modifiers, source)
        val listed = element.childList().filterIsInstance<PsiParameterList>().firstOrNull()
        val params = listed?.parameters?.map(::param) ?: components
        val name = source.of(element.nameIdentifier!!)
        val withOverride = if (overrides(modifiers)) modifiers.copy(texts = modifiers.texts + OVERRIDE) else modifiers
        return if (element.isConstructor) {
            DeclShape("constructor", name, withOverride, params, sig = sig)
        } else {
            DeclShape("fun", name, withOverride, params, returns = element.returnTypeElement?.let(types::typeText), sig = sig)
        }
    }

    fun field(element: PsiField): DeclShape = variable(element, element.nameIdentifier)

    fun enumConstant(element: PsiEnumConstant): DeclShape {
        val name = source.of(element.nameIdentifier)
        return DeclShape("enum_entry", name, modifiers(element.modifierList), sig = name)
    }

    /** A record component: a property declared with the class, as a Kotlin constructor property is. */
    fun recordComponent(element: PsiRecordComponent): DeclShape =
        DeclShape(
            "property", source.of(element.nameIdentifier!!), modifiers(element.modifierList),
            returns = element.typeElement?.let(types::typeText), sig = JsText.squash(source.of(element)),
        )

    fun initializer(element: PsiClassInitializer): DeclShape {
        val modifiers = modifiers(element.modifierList)
        return DeclShape("init", "init", modifiers, sig = if ("static" in modifiers.texts) "static init" else "init")
    }

    /** A field or local variable; `var` has the type of its initializer. */
    fun variable(element: PsiVariable, name: PsiElement?): DeclShape {
        val first = declarator(element)
        val modifiers = modifiers(first.childList().filterIsInstance<PsiModifierList>().firstOrNull())
        val type = first.childList().filterIsInstance<PsiTypeElement>().firstOrNull()
        val returns = types.of(type, element.initializer, element).ifEmpty { null }
        val nameText = name?.let(source::of) ?: "?"
        val sig = JavaSignature.prefixed(modifiers, "${type?.let { JsText.squash(source.of(it)) } ?: ""} $nameText")
        return DeclShape("property", nameText, modifiers, returns = returns, sig = sig)
    }

    /** The parameter's type (an array for varargs and `s[]`), name and varargs flag. */
    fun param(parameter: PsiParameter): ParamFact =
        ParamFact(parameter.name, types.of(parameter.typeElement, null, parameter), vararg = parameter.isVarArgs)

    // `int a, b;` holds the type and modifiers once, in the first declarator.
    private fun declarator(element: PsiVariable): PsiVariable {
        var current = element
        while (current.childList().none { it is PsiTypeElement }) {
            current = generateSequence(current.prevSibling) { it.prevSibling }.filterIsInstance<PsiVariable>().firstOrNull() ?: return element
        }
        return current
    }

    private fun modifiers(list: PsiModifierList?) = JavaModifiers.of(list, source)

    private fun overrides(modifiers: Modifiers) = modifiers.texts.any { it == "@Override" || it.endsWith(".Override") }

    // `extends` and `implements` entries by name; a qualified one stays qualified so it can be resolved from anywhere.
    private fun supertypes(element: PsiClass): List<String> =
        (element.extendsList?.referenceElements.orEmpty().toList() + element.implementsList?.referenceElements.orEmpty().toList()).map(::supertypeName)

    private fun supertypeName(reference: PsiJavaCodeReferenceElement) = JavaTypeNames.dotted(reference)


    companion object {
        /** Name of an anonymous class; no declared name can contain `<`. */
        const val ANONYMOUS = "<anonymous>"

        private const val OVERRIDE = "override"
    }
}
