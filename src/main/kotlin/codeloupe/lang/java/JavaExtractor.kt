package codeloupe.lang.java

import codeloupe.lang.DeclFact
import codeloupe.lang.FileFacts
import codeloupe.lang.ImportFact
import codeloupe.lang.JsText
import codeloupe.lang.ParamFact
import codeloupe.lang.RefFact
import codeloupe.platform.Sha1
import codeloupe.lang.kotlin.DeclShape
import codeloupe.lang.kotlin.Kdoc
import codeloupe.lang.kotlin.LocalScopes
import codeloupe.lang.kotlin.Reference
import codeloupe.lang.kotlin.Source
import codeloupe.lang.kotlin.childList
import org.jetbrains.kotlin.com.intellij.psi.PsiCatchSection
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassInitializer
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstantInitializer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiForStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiForeachStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiIfStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNameValuePair
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiPackageStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiPatternVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiRecordComponent
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceParameterList
import org.jetbrains.kotlin.com.intellij.psi.PsiTryStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiWhileStatement

/** Java PSI -> facts of one file: package, imports, declarations, references; the same facts the Kotlin extractor makes. */
internal class JavaExtractor(private val source: Source) {
    private val scopes = LocalScopes()
    private val localTypes = JavaLocalTypes(source, scopes)
    private val lambdaTypes = JavaLambdaTypes(localTypes)
    private val shapes = JavaShapes(source, localTypes)
    private val references = JavaReferences(source, scopes, localTypes)
    private var packageName = ""
    private val imports = ArrayList<ImportFact>()
    private val decls = ArrayList<DeclFact>()
    private val refs = ArrayList<RefFact>()
    private var errors = 0

    /** Enclosing declarations, innermost last. */
    private val stack = ArrayList<Int>()

    fun extract(file: PsiJavaFile): FileFacts {
        walk(file)
        return FileFacts(packageName, imports, decls, refs, errors)
    }

    private fun walk(element: PsiElement) {
        if (element is PsiErrorElement) errors++
        when (element) {
            is PsiComment -> Unit
            is PsiPackageStatement -> packageName = element.packageReference?.let { JsText.removeSpaces(source.of(it)) } ?: ""
            is PsiImportStatementBase -> addImport(element)
            is PsiTypeParameter -> walkChildren(element)
            is PsiClass -> classLike(element)
            is PsiEnumConstant -> enumConstant(element)
            is PsiField -> nested(declare(element, shapes.field(element)), element)
            is PsiMethod -> method(element)
            is PsiClassInitializer -> nested(declare(element, shapes.initializer(element)), element)
            is PsiLocalVariable -> localVariable(element)
            is PsiPatternVariable -> patternVariable(element)
            is PsiRecordComponent, is PsiParameter -> walkChildren(element)
            is PsiLambdaExpression -> scopes.within(lambdaParameters(element)) { walkChildren(element) }
            is PsiForeachStatement -> forEach(element)
            is PsiCatchSection -> scopes.within(parameters(listOfNotNull(element.parameter))) { walkChildren(element) }
            is PsiCodeBlock, is PsiForStatement, is PsiTryStatement, is PsiIfStatement, is PsiWhileStatement ->
                scopes.within(emptyMap()) { walkChildren(element) }
            is PsiNewExpression -> newExpression(element)
            is PsiMethodCallExpression -> methodCall(element)
            is PsiMethodReferenceExpression -> methodReference(element)
            is PsiReferenceExpression -> referenceExpression(element)
            is PsiJavaCodeReferenceElement -> typeReference(element, TYPE, null)
            is PsiNameValuePair -> annotationElement(element)
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

    private fun nested(index: Int, element: PsiElement, skip: (PsiElement) -> Boolean = { false }) {
        stack += index
        for (child in element.childList()) if (!skip(child)) walk(child)
        stack.removeLast()
    }

    private fun classLike(element: PsiClass) {
        stack += declare(element, shapes.classLike(element))
        decls[innermost()].bodyOpen = element.lBrace?.textRange?.startOffset ?: -1
        decls[innermost()].bodyClose = element.rBrace?.textRange?.startOffset ?: -1
        // Record components follow their class directly, before its members.
        for (component in element.recordComponents) declare(component, shapes.recordComponent(component))
        scopes.inClassBody { walkChildren(element) }
        stack.removeLast()
    }

    private fun enumConstant(element: PsiEnumConstant) {
        stack += declare(element, shapes.enumConstant(element))
        for (child in element.childList()) {
            if (child is PsiEnumConstantInitializer) scopes.inClassBody { walkChildren(child) } else walk(child)
        }
        stack.removeLast()
    }

    private fun method(element: PsiMethod) {
        // Half-typed `void (int x) {}`: no name to declare, but what is inside it still refers to things.
        if (element.nameIdentifier == null) return walkChildren(element)
        val components = if (element.isConstructor) componentParams(element.containingClass) else emptyList()
        val listed = element.parameterList.parameters.map(shapes::param)
        val bindings = bindings((listed.ifEmpty { components }).map { it.name to it.type })
        scopes.within(bindings) { nested(declare(element, shapes.method(element, components)), element) }
    }

    private fun componentParams(owner: PsiClass?): List<ParamFact> =
        owner?.recordComponents?.map { ParamFact(it.name, it.typeElement?.let(localTypes::typeText).orEmpty()) }.orEmpty()

    private fun localVariable(element: PsiLocalVariable) {
        val index = declare(element, shapes.variable(element, element.nameIdentifier))
        nested(index, element)
        bindName(element.nameIdentifier, decls[index].returns.orEmpty())
    }

    private fun patternVariable(element: PsiPatternVariable) {
        walkChildren(element)
        bindName(element.nameIdentifier, element.typeElement?.let { localTypes.of(it, null) }.orEmpty())
    }

    // The loop variable is not visible in the iterated expression.
    private fun forEach(element: PsiForeachStatement) {
        val range = element.iteratedValue
        val variable = element.iterationParameter
        val declared = variable.typeElement
        val bound = if (declared != null && localTypes.isVar(declared)) localTypes.elementOf(range) else localTypes.of(declared, null)
        val bindings = mapOf(variable.name to bound)
        for (child in element.childList()) {
            if (child == range) walk(child) else scopes.within(bindings) { walk(child) }
        }
    }

    private fun parameters(list: List<PsiParameter>): Map<String, String> =
        bindings(list.map { it.name to localTypes.of(it.typeElement, null) })

    private fun bindings(pairs: List<Pair<String, String>>): Map<String, String> = pairs.toMap(HashMap())

    private fun lambdaParameters(lambda: PsiLambdaExpression): Map<String, String> {
        val list = lambda.parameterList.parameters.toList()
        val implied = if (list.size == 1 && list[0].typeElement == null) lambdaTypes.parameter(lambda) else ""
        return bindings(list.map { it.name to (if (it.typeElement == null) implied else localTypes.of(it.typeElement, null)) })
    }

    private fun bindName(identifier: PsiElement?, type: String) {
        if (identifier != null) scopes.bind(source.of(identifier), type)
    }

    private fun methodCall(call: PsiMethodCallExpression) {
        val callee = call.methodExpression
        callee.qualifierExpression?.let(::walk)
        callee.childList().filterIsInstance<PsiReferenceParameterList>().forEach(::walkChildren)
        references.call(callee, call.argumentList.expressionCount)?.let(::addRef)
        walk(call.argumentList)
    }

    private fun referenceExpression(expression: PsiReferenceExpression) {
        expression.qualifierExpression?.let(::walk)
        expression.childList().filterIsInstance<PsiReferenceParameterList>().forEach(::walkChildren)
        references.name(expression)?.let(::addRef)
    }

    private fun methodReference(expression: PsiMethodReferenceExpression) {
        walkChildren(expression)
        references.methodReference(expression)?.let(::addRef)
    }

    // `new Type(args)` calls the constructor; an anonymous body brings a local object extending Type.
    private fun newExpression(expression: PsiNewExpression) {
        expression.qualifier?.let(::walk)
        val anonymous = expression.anonymousClass
        val type = expression.classReference ?: anonymous?.baseClassReference
        if (type == null) {
            walkChildren(expression)
            return
        }
        val arguments = anonymous?.argumentList ?: expression.argumentList
        typeReference(type, CALL, arguments?.expressionCount)
        arguments?.let(::walk)
        anonymous?.let { body ->
            scopes.inClassBody { nested(declare(body, shapes.anonymous(body)), body) { it === type || it === arguments } }
        }
    }

    private fun typeReference(reference: PsiJavaCodeReferenceElement, kind: String, args: Int?) {
        (reference.qualifier as? PsiJavaCodeReferenceElement)?.let { typeReference(it, TYPE, null) }
        references.segment(reference, kind, args)?.let(::addRef)
        reference.childList().filterIsInstance<PsiReferenceParameterList>().forEach(::walkChildren)
    }

    private fun annotationElement(pair: PsiNameValuePair) {
        references.annotationElement(pair)?.let(::addRef)
        for (child in pair.childList()) if (child !== pair.nameIdentifier) walk(child)
    }

    private fun addImport(statement: PsiImportStatementBase) {
        val reference = statement.importReference ?: return
        imports += ImportFact(JsText.removeSpaces(source.of(reference)), null, statement.isOnDemand)
    }

    private fun declare(element: PsiElement, shape: DeclShape): Int {
        val span = JavaSpan.of(element)
        val lines = source.lines
        val declStart = lines.line(span.start)
        val documentation = Kdoc.offset(element, span, source)
        decls += DeclFact(
            kind = shape.kind,
            name = shape.name,
            container = stack.joinToString(".") { decls[it].name },
            receiver = null,
            params = shape.params,
            returns = shape.returns,
            modifiers = shape.modifiers.texts,
            supertypes = shape.supertypes,
            start = documentation?.let(lines::line) ?: declStart,
            declStart = declStart,
            end = lines.endLine(span.start, span.end),
            sig = shape.sig ?: shape.name,
            hash = Sha1.hex(source.of(span)).take(HASH_LENGTH),
            // An anonymous class and all inside it are code, wherever the expression stands.
            local = shape.name == JavaShapes.ANONYMOUS || stack.lastOrNull()?.let { decls[it].local || decls[it].kind in CODE_KINDS } == true,
            parent = innermost(),
        ).also {
            it.startOffset = documentation ?: span.start
            it.endOffset = span.end
            it.nameOffset = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset ?: -1
        }
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
        const val TYPE = "type"
        const val CALL = "call"

        /** Declarations whose members are local: code, not API. */
        val CODE_KINDS = setOf("fun", "property", "constructor", "init")
    }
}
