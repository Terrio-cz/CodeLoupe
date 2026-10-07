package codeloupe.lang.kotlin

import codeloupe.lang.DeclFact
import codeloupe.lang.FileFacts
import codeloupe.lang.ImportFact
import codeloupe.lang.JsText
import codeloupe.lang.RefFact
import codeloupe.platform.Sha1
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Kotlin PSI -> facts of one file: package, imports, declarations, references. */
internal class KotlinExtractor(private val source: Source) {
    private val shapes = DeclShapes(source)
    private val references = References(source)
    private var packageName = ""
    private val imports = ArrayList<ImportFact>()
    private val decls = ArrayList<DeclFact>()
    private val refs = ArrayList<RefFact>()
    private var errors = 0

    /** Enclosing declarations, innermost last; [OBJECT_LITERAL] for an anonymous object. */
    private val stack = ArrayList<Int>()

    fun extract(file: KtFile): FileFacts {
        walk(file)
        return FileFacts(packageName, imports, decls, refs, errors)
    }

    private fun walk(element: PsiElement) {
        if (element is PsiErrorElement) errors++
        when (element) {
            is PsiComment -> Unit
            is KtPackageDirective -> packageName = element.packageNameExpression?.let { JsText.squash(source.of(it)) } ?: ""
            is KtImportDirective -> addImport(element)
            is KtEnumEntry -> nested(declare(element, shapes.enumEntry(element)), element)
            is KtObjectDeclaration -> if (element.isObjectLiteral()) nested(OBJECT_LITERAL, element) else classLike(element)
            is KtClassOrObject -> classLike(element)
            // A function without a name is an anonymous function: an expression, not a declaration.
            is KtNamedFunction ->
                if (element.nameIdentifier == null) walkChildren(element) else nested(declare(element, shapes.function(element)), element)
            is KtProperty ->
                if (isWhenSubject(element)) walkChildren(element) else nested(declare(element, shapes.property(element)), element)
            is KtDestructuringDeclaration ->
                if (element.parent is KtParameter) walkChildren(element) else nested(declare(element, shapes.destructuring(element)), element)
            is KtSecondaryConstructor -> nested(declare(element, shapes.constructor(element, ownerName())), element)
            // PSI wraps each top-level statement of a script in an initializer; the index sees plain statements.
            is KtScriptInitializer -> walkChildren(element)
            is KtAnonymousInitializer -> nested(declare(element, shapes.initializer(element)), element)
            is KtTypeAlias -> {
                declare(element, shapes.typeAlias(element))
                walkChildren(element)
            }
            is KtSimpleNameExpression -> references.of(element)?.let(::addRef)
            is KtParameter -> walkParameter(element)
            is KtClassLiteralExpression -> walkClassLiteral(element)
            else -> walkChildren(element)
        }
    }

    private fun walkChildren(element: PsiElement) {
        var child = element.firstChild
        while (child != null) {
            walk(child)
            child = child.nextSibling
        }
    }

    private fun nested(index: Int, element: PsiElement) {
        stack += index
        walkChildren(element)
        stack.removeLast()
    }

    // Constructor properties follow their class directly, before its members.
    private fun classLike(element: KtClassOrObject) {
        stack += declare(element, shapes.classLike(element))
        for (parameter in element.primaryConstructorParameters) {
            if (parameter.hasValOrVar()) declare(parameter, shapes.constructorProperty(parameter))
        }
        walkChildren(element)
        stack.removeLast()
    }

    // Catch and setter parameter names are recorded as uses, like the index has always done.
    private fun walkParameter(parameter: KtParameter) {
        val listOwner = parameter.parent?.parent
        val nameIsUse = listOwner is KtCatchClause || (listOwner is KtPropertyAccessor && listOwner.isSetter)
        for (child in parameter.childList()) {
            if (nameIsUse && child == parameter.nameIdentifier) addRef(references.parameterName(child)) else walk(child)
        }
    }

    private fun walkClassLiteral(literal: KtClassLiteralExpression) {
        for (child in literal.childList()) {
            if (child.node.elementType == KtTokens.CLASS_KEYWORD) references.classLiteral(literal, child)?.let(::addRef) else walk(child)
        }
    }

    private fun isWhenSubject(property: KtProperty): Boolean = (property.parent as? KtWhenExpression)?.subjectVariable == property

    private fun ownerName(): String = stack.lastOrNull()?.takeIf { it >= 0 }?.let { decls[it].name } ?: "constructor"

    private fun addImport(directive: KtImportDirective) {
        val reference = directive.importedReference ?: return
        val alias = directive.alias?.nameIdentifier?.let { JsText.bare(source.of(it)) }
        imports += ImportFact(JsText.squash(source.of(reference)), alias, directive.isAllUnder)
    }

    private fun declare(element: PsiElement, shape: DeclShape): Int {
        val span = Span.of(element)
        val lines = source.lines
        val declStart = lines.line(span.start)
        decls += DeclFact(
            kind = shape.kind,
            name = JsText.bare(shape.name),
            container = stack.joinToString(".") { if (it >= 0) decls[it].name else "<anonymous>" },
            receiver = shape.receiver,
            params = shape.params,
            returns = shape.returns,
            modifiers = shape.modifiers.texts,
            supertypes = shape.supertypes,
            start = Kdoc.line(element, span, source) ?: declStart,
            declStart = declStart,
            end = lines.endLine(span.start, span.end),
            sig = shape.sig ?: Signature.of(element, span, shape.modifiers, source),
            hash = Sha1.hex(source.of(span)).take(HASH_LENGTH),
            local = stack.any { it < 0 || decls[it].kind in CODE_KINDS },
            parent = innermost(),
        )
        return decls.size - 1
    }

    private fun addRef(reference: Reference) {
        val lines = source.lines
        refs += RefFact(reference.name, lines.line(reference.offset), lines.column(reference.offset) + 1, reference.kind, reference.recv, innermost())
    }

    private fun innermost(): Int = stack.lastOrNull { it >= 0 } ?: -1

    private companion object {
        const val OBJECT_LITERAL = -1
        const val HASH_LENGTH = 10

        /** Declarations whose members are local: code, not API. */
        val CODE_KINDS = setOf("fun", "property", "constructor", "init")
    }
}
