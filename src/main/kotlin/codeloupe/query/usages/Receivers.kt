package codeloupe.query.usages

import codeloupe.lang.TypeSpec
import codeloupe.query.DeclMatch
import codeloupe.query.DeclRow
import codeloupe.query.RefRow

/**
 * The receiver of `x.m()` from syntax alone: `this`/`super`, a type or package used as qualifier, the type spec
 * the extractor recorded (locals, calls, chains), or a property's declared type. Anything else is unknown.
 */
internal class Receivers(
    private val cache: IndexCache,
    private val types: Types,
    private val implicit: ImplicitScope,
    private val specs: TypeSpecs,
    private val promote: (String, Resolution) -> Resolution,
) {
    fun of(ref: RefRow, file: FileScope, chain: List<DeclRow>): ReceiverType {
        val recv = ref.recv ?: return ReceiverType.Unknown
        val at = chain.firstOrNull()
        return when {
            recv == "this" -> ref.recvType?.let { lambdaReceiver(it, file, at) } ?: thisType(chain, file, null)
            recv.startsWith("this@") -> thisType(chain, file, recv.removePrefix("this@"))
            recv == "super" || recv.startsWith("super<") -> superType(chain)
            // A capitalised name the spec cannot type is looked up as a property, object or library type.
            else -> qualifier(recv, file, at)
                ?: ref.recvType?.let { spec -> specs.text(spec, file, at)?.let(::instance) }
                ?: if (NAME.matches(recv) && (ref.recvType == null || recv.first().isUpperCase())) property(recv, file, chain) else ReceiverType.Unknown
        }
    }

    fun fromSpec(spec: String, file: FileScope, at: DeclRow?): ReceiverType =
        specs.text(spec, file, at)?.let { instance(it) } ?: ReceiverType.Unknown

    /**
     * The implicit receiver a lambda brought (`x.apply { m() }`, a DSL builder), null when it brings none. A lambda
     * whose parameter takes no receiver hands on the enclosing lambda's (`&…|outer`).
     */
    fun lambdaReceiver(spec: String, file: FileScope, at: DeclRow?): ReceiverType? {
        if (spec.isEmpty() || spec[0] != TypeSpec.LAMBDA_RECEIVER) return fromSpec(spec, file, at)
        return when (val receiver = specs.lambdaReceiver(spec, file)) {
            is LambdaReceiver.Typed -> instance(receiver.type)
            // A library receiver comes first, the enclosing lambda's (if any) after it.
            LambdaReceiver.Library -> when (val outer = spec.substringAfter(TypeSpec.OR, "").let { if (it.isEmpty()) null else lambdaReceiver(it, file, at) }) {
                is ReceiverType.Instance -> outer.copy(open = true)
                null -> ReceiverType.Instance(emptyList(), emptySet())
                else -> outer
            }
            LambdaReceiver.Unknown -> ReceiverType.Unknown
            LambdaReceiver.None -> spec.substringAfter(TypeSpec.OR, "").let { if (it.isEmpty()) null else lambdaReceiver(it, file, at) }
        }
    }

    // `Type`, `Outer.Inner`, `pkg.Type`, `pkg` before a top-level name.
    private fun qualifier(recv: String, file: FileScope, at: DeclRow?): ReceiverType? {
        if (!DOTTED.matches(recv)) return null
        val first = recv.substringBefore('.')
        if (first.first().isUpperCase() || '.' in recv) {
            val resolved = types.resolve(recv, file, at)
            if (resolved.isNotEmpty()) return ReceiverType.Static(resolved)
        }
        return if ('.' in recv && first in packageRoots(file)) ReceiverType.Package(recv) else null
    }

    // A lowercase qualifier is a package only when it starts like the packages this file knows.
    private fun packageRoots(file: FileScope): Set<String> =
        (file.imports.map { it.fqn } + file.packageName).map { it.substringBefore('.') }.toSet() + PLATFORM_ROOTS

    // A capitalised name nothing in reach declares, and no indexed type bears, is a library type or object:
    // `ByteBuffer.allocate(8)`.
    private fun property(name: String, file: FileScope, chain: List<DeclRow>): ReceiverType {
        val properties = promote(name, implicit.find(name, file, chain, accept = { it.kind == "property" || it.kind == "object" || it.kind == "enum_entry" }))
        if (properties.decls.isEmpty() && name.first().isUpperCase() && cache.named(name).none { it.kind in Kinds.CLASSIFIERS }) {
            return ReceiverType.Static(emptyList())
        }
        if (!properties.complete || properties.decls.isEmpty()) return ReceiverType.Unknown
        if (properties.decls.all { it.kind == "object" }) return ReceiverType.Static(properties.decls)
        if (properties.decls.all { it.kind == "enum_entry" }) return implicit.instanceOf(properties.decls, emptySet())
        val typed = properties.decls.map { p -> specs.declaredType(p)?.let(::instance) as? ReceiverType.Instance ?: return ReceiverType.Unknown }
        return ReceiverType.Instance(typed.flatMap { it.types }.distinct(), typed.flatMap { it.names }.toSet())
    }

    private fun instance(type: TypeSpecs.TypeText): ReceiverType {
        val simple = DeclMatch.baseType(type.text).substringAfterLast(' ').substringAfterLast('.')
        if (simple.isEmpty() || Kinds.isTypeParameter(simple)) return ReceiverType.Unknown
        return implicit.instanceOf(types.resolve(type.text, type.file, type.at), setOf(simple))
    }

    private fun thisType(chain: List<DeclRow>, file: FileScope, label: String?): ReceiverType {
        val outer = chain.firstOrNull { (it.kind in Kinds.CLASSIFIERS || it.receiver != null) && (label == null || it.name == label) }
        return outer?.let { implicit.receiverTypes(it, file) } ?: ReceiverType.Unknown
    }

    private fun superType(chain: List<DeclRow>): ReceiverType {
        val type = chain.firstOrNull { it.kind in Kinds.CLASSIFIERS } ?: return ReceiverType.Unknown
        val direct = types.direct(type)
        return implicit.instanceOf(direct.flatMap { it.second }, direct.map { it.first }.toSet())
    }

    private companion object {
        val PLATFORM_ROOTS = setOf("java", "javax", "kotlin", "kotlinx")
        val NAME = Regex("""[A-Za-z_]\w*""")
        val DOTTED = Regex("""[A-Za-z_]\w*(\.[A-Za-z_]\w*)*""")
    }
}
