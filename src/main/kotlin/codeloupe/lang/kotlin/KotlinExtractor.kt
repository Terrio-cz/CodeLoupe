package codeloupe.lang.kotlin

import codeloupe.lang.DeclFact
import codeloupe.lang.FileFacts
import codeloupe.lang.ImportFact
import codeloupe.lang.JsText
import codeloupe.lang.RefFact
import codeloupe.lang.TypeSpec
import codeloupe.platform.Sha1
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
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
import org.jetbrains.kotlin.psi.KtSuperTypeList
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Kotlin PSI -> facts of one file: package, imports, declarations, references. */
internal class KotlinExtractor(private val source: Source) {
    private val shapes = DeclShapes(source)
    private val scopes = LocalScopes()
    private val localTypes = LocalTypes(source, scopes)
    private val lambdaTypes = LambdaTypes(localTypes)
    private val references = References(source, scopes, localTypes)
    private var packageName = ""
    private val imports = ArrayList<ImportFact>()
    private val decls = ArrayList<DeclFact>()
    private val refs = ArrayList<RefFact>()
    private var errors = 0

    /** Enclosing declarations, innermost last. */
    private val stack = ArrayList<Int>()

    /** Plain (non-property) primary constructor parameters of the enclosing classes, visible in their initializers. */
    private val constructorScopes = ArrayList<Map<String, String>>()

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
            is KtObjectDeclaration -> if (element.isObjectLiteral()) objectLiteral(element) else classLike(element)
            is KtClassOrObject -> classLike(element)
            // A function without a name is an anonymous function: an expression, not a declaration.
            is KtNamedFunction -> function(element)
            is KtProperty -> property(element)
            is KtDestructuringDeclaration ->
                if (element.parent is KtParameter) walkChildren(element) else destructuring(element)
            is KtSecondaryConstructor ->
                scopes.within(parameters(element.valueParameters)) { nested(declare(element, shapes.constructor(element, ownerName())), element) }
            is KtPropertyAccessor -> scopes.within(parameters(element.valueParameters)) { walkChildren(element) }
            is KtFunctionLiteral ->
                scopes.within(lambdaParameters(element)) { scopes.withReceiver(lambdaReceiver(element)) { walkChildren(element) } }
            is KtForExpression -> forLoop(element)
            is KtCatchClause -> scopes.within(parameters(listOfNotNull(element.catchParameter))) { walkChildren(element) }
            is KtBlockExpression, is KtWhenExpression -> scopes.within(emptyMap()) { walkChildren(element) }
            // PSI wraps each top-level statement of a script in an initializer; the index sees plain statements.
            is KtScriptInitializer -> walkChildren(element)
            is KtAnonymousInitializer -> inConstructorScope { nested(declare(element, shapes.initializer(element)), element) }
            is KtSuperTypeList -> inConstructorScope { walkChildren(element) }
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
        constructorScopes += parameters(element.primaryConstructorParameters.filter { !it.hasValOrVar() })
        scopes.inClassBody { walkChildren(element) }
        constructorScopes.removeLast()
        stack.removeLast()
    }

    private fun objectLiteral(element: KtObjectDeclaration) {
        constructorScopes += emptyMap()
        scopes.inClassBody { nested(declare(element, shapes.objectLiteral(element)), element) }
        constructorScopes.removeLast()
    }

    private fun function(element: KtNamedFunction) {
        if (element.nameIdentifier == null) {
            scopes.within(parameters(element.valueParameters)) { walkChildren(element) }
            return
        }
        // Bound before its body: a local function may call itself.
        if (element.isLocal) scopes.bind(JsText.bare(source.of(element.nameIdentifier!!)), "")
        scopes.within(parameters(element.valueParameters)) {
            val shape = shapes.function(element)
            val body = element.bodyExpression?.takeIf { shape.returns == null && !element.hasBlockBody() }
            nested(declare(element, shape.copy(returns = shape.returns ?: body?.let(localTypes::ofExpression)?.ifEmpty { null })), element)
        }
    }

    private fun property(element: KtProperty) {
        val type = { localTypes.of(element.typeReference, element.initializer) }
        when {
            isWhenSubject(element) -> {
                walkChildren(element)
                bindName(element.nameIdentifier, type())
            }
            element.parent is KtClassBody -> inConstructorScope { nested(declare(element, propertyShape(element)), element) }
            else -> {
                nested(declare(element, propertyShape(element)), element)
                if (element.isLocal) bindName(element.nameIdentifier, type())
            }
        }
    }

    // An undeclared type is recorded as the spec of its initializer.
    private fun propertyShape(element: KtProperty): DeclShape {
        val shape = shapes.property(element)
        return if (shape.returns != null) shape else shape.copy(returns = localTypes.ofProperty(element))
    }

    private fun destructuring(element: KtDestructuringDeclaration) {
        nested(declare(element, shapes.destructuring(element)), element)
        for (entry in element.entries) bindName(entry.nameIdentifier, localTypes.of(entry.typeReference, null))
    }

    // The loop variable is not visible in the range it iterates.
    private fun forLoop(element: KtForExpression) {
        val range = element.loopRange
        val bindings = HashMap<String, String>()
        element.loopParameter?.let { p ->
            p.nameIdentifier?.let { bindings[JsText.bare(source.of(it))] = localTypes.of(p.typeReference, null).ifEmpty { localTypes.elementOf(range) } }
            p.destructuringDeclaration?.entries?.forEach { e -> e.nameIdentifier?.let { bindings[JsText.bare(source.of(it))] = "" } }
        }
        for (child in element.childList()) {
            if (child == range?.parent || child == range) walk(child) else scopes.within(bindings) { walk(child) }
        }
    }

    private fun inConstructorScope(block: () -> Unit) = scopes.within(constructorScopes.lastOrNull() ?: emptyMap()) { block() }

    private fun parameters(list: List<KtParameter>): Map<String, String> {
        val bindings = HashMap<String, String>()
        for (p in list) {
            p.nameIdentifier?.let { bindings[JsText.bare(source.of(it))] = localTypes.of(p.typeReference, null) }
            p.destructuringDeclaration?.entries?.forEach { e -> e.nameIdentifier?.let { bindings[JsText.bare(source.of(it))] = "" } }
        }
        return bindings
    }

    private fun lambdaParameters(literal: KtFunctionLiteral): Map<String, String> {
        val implied = lambdaTypes.parameter(literal)
        if (literal.valueParameterList == null) return if (lambdaTypes.hasNoParameter(literal)) emptyMap() else mapOf("it" to implied)
        val bindings = HashMap(parameters(literal.valueParameters))
        val typed = literal.valueParameters.getOrNull(if (lambdaTypes.isIndexed(literal)) 1 else 0)
        if (typed != null && typed.typeReference == null && literal.valueParameters.size == (if (lambdaTypes.isIndexed(literal)) 2 else 1)) {
            typed.nameIdentifier?.let { bindings[JsText.bare(source.of(it))] = implied }
        }
        return bindings
    }

    // A lambda whose parameter turns out to take no receiver leaves the outer implicit receiver in place.
    private fun lambdaReceiver(literal: KtFunctionLiteral): String? {
        val spec = lambdaTypes.receiver(literal) ?: return null
        if (spec.firstOrNull() != TypeSpec.LAMBDA_RECEIVER) return spec
        return scopes.implicitReceiver?.let { "$spec${TypeSpec.OR}$it" } ?: spec
    }

    private fun bindName(identifier: PsiElement?, type: String) {
        if (identifier != null) scopes.bind(JsText.bare(source.of(identifier)), type)
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

    private fun ownerName(): String = stack.lastOrNull()?.let { decls[it].name } ?: "constructor"

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
            container = stack.joinToString(".") { decls[it].name },
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
            // An object expression and all inside it are code, wherever the expression stands.
            local = shape.name == DeclShapes.ANONYMOUS || stack.lastOrNull()?.let { decls[it].local || decls[it].kind in CODE_KINDS } == true,
            parent = innermost(),
        )
        return decls.size - 1
    }

    private fun addRef(reference: Reference) {
        val lines = source.lines
        refs += RefFact(
            reference.name, lines.line(reference.offset), lines.column(reference.offset) + 1, reference.kind, reference.recv, innermost(),
            reference.bind, reference.recvType, reference.args,
        )
    }

    private fun innermost(): Int = stack.lastOrNull() ?: -1

    private companion object {
        const val HASH_LENGTH = 10

        /** Declarations whose members are local: code, not API. */
        val CODE_KINDS = setOf("fun", "property", "constructor", "init")
    }
}
