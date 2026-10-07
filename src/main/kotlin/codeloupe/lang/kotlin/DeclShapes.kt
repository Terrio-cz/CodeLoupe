package codeloupe.lang.kotlin

import codeloupe.lang.JsText
import codeloupe.lang.ParamFact
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

/** Reads kind, name, modifiers, parameters and types of each Kotlin declaration form. */
internal class DeclShapes(private val source: Source) {
    fun classLike(element: KtClassOrObject): DeclShape {
        val modifiers = modifiers(element)
        val kind = when {
            element is KtObjectDeclaration -> if (element.isCompanion()) "companion" else "object"
            element is KtClass && element.isInterface() -> "interface"
            "enum" in modifiers.texts -> "enum"
            "annotation" in modifiers.texts -> "annotation"
            else -> "class"
        }
        val name = element.nameIdentifier?.let(source::of) ?: "Companion"
        return DeclShape(kind, name, modifiers, supertypes = supertypes(element))
    }

    /** A `val`/`var` parameter of a primary constructor: a property whose signature is the whole parameter. */
    fun constructorProperty(parameter: KtParameter): DeclShape =
        DeclShape(
            "property", nameOf(parameter.nameIdentifier), modifiers(parameter),
            returns = type(parameter.typeReference), sig = Signature.ofWhole(Span.of(parameter), source),
        )

    fun function(element: KtNamedFunction): DeclShape =
        DeclShape(
            "fun", nameOf(element.nameIdentifier), modifiers(element), params(element.valueParameters),
            receiver = receiverType(element.receiverTypeReference), returns = type(element.typeReference),
        )

    fun property(element: KtProperty): DeclShape =
        DeclShape(
            "property", nameOf(element.nameIdentifier), modifiers(element),
            receiver = receiverType(element.receiverTypeReference), returns = type(element.typeReference),
        )

    /** `val (a, b) = pair`: one unnamed property, as the index has always recorded it. */
    fun destructuring(element: KtDestructuringDeclaration): DeclShape = DeclShape("property", "?", modifiers(element))

    fun constructor(element: KtSecondaryConstructor, owner: String): DeclShape =
        DeclShape("constructor", owner, modifiers(element), params(element.valueParameters))

    fun initializer(element: KtAnonymousInitializer): DeclShape = DeclShape("init", "init", modifiers(element))

    fun typeAlias(element: KtTypeAlias): DeclShape =
        DeclShape("typealias", nameOf(element.nameIdentifier), modifiers(element), returns = type(element.getTypeReference()))

    fun enumEntry(element: KtEnumEntry): DeclShape {
        val name = nameOf(element.nameIdentifier)
        return DeclShape("enum_entry", name, modifiers(element), sig = name)
    }

    private fun modifiers(element: org.jetbrains.kotlin.psi.KtModifierListOwner) = Modifiers.of(element.modifierList, source)

    private fun nameOf(identifier: org.jetbrains.kotlin.com.intellij.psi.PsiElement?): String = identifier?.let(source::of) ?: "?"

    private fun params(parameters: List<KtParameter>): List<ParamFact> =
        parameters.map { ParamFact(JsText.bare(nameOf(it.nameIdentifier)), type(it.typeReference) ?: "", it.hasDefaultValue(), it.isVarArg) }

    private fun type(reference: KtTypeReference?): String? = reference?.let { JsText.squash(source.of(it)) }

    // Annotations and `suspend` on a receiver are not part of its type text.
    private fun receiverType(reference: KtTypeReference?): String? {
        if (reference == null) return null
        val element = if (reference.modifierList != null) reference.typeElement ?: reference else reference
        return JsText.squash(source.of(element))
    }

    // The first name of each supertype (`a.b.C<X>` -> `a`); delegations (`I by impl`) are not listed.
    private fun supertypes(element: KtClassOrObject): List<String> = element.superTypeListEntries.mapNotNull { entry ->
        if (entry is KtDelegatedSuperTypeEntry) return@mapNotNull null
        var type = entry.typeReference?.typeElement as? KtUserType ?: return@mapNotNull null
        while (true) type = type.qualifier ?: break
        type.referenceExpression?.let { JsText.bare(source.of(it)) }
    }
}
