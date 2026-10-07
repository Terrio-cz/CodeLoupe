package codeloupe.query.usages

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
) {
    fun of(ref: RefRow, file: FileScope, chain: List<DeclRow>): ReceiverType {
        val recv = ref.recv ?: return ReceiverType.Unknown
        val at = chain.firstOrNull()
        return when {
            recv == "this" -> thisType(chain, file, null)
            recv.startsWith("this@") -> thisType(chain, file, recv.removePrefix("this@"))
            recv == "super" || recv.startsWith("super<") -> superType(chain)
            else -> qualifier(recv, file, at)
                ?: ref.recvType?.let { fromSpec(it, file, at) }
                ?: if (NAME.matches(recv)) property(recv, file, chain) else ReceiverType.Unknown
        }
    }

    /** The innermost implicit receiver a lambda brought (`x.apply { m() }`), from its recorded spec. */
    fun fromSpec(spec: String, file: FileScope, at: DeclRow?): ReceiverType =
        specs.text(spec, file, at)?.let { instance(it) } ?: ReceiverType.Unknown

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

    // A capitalised name nothing in reach declares is a library type or object: `ByteBuffer.allocate(8)`.
    private fun property(name: String, file: FileScope, chain: List<DeclRow>): ReceiverType {
        val properties = implicit.find(name, file, chain, accept = { it.kind == "property" || it.kind == "object" || it.kind == "enum_entry" })
        if (!properties.complete && name.first().isUpperCase()) return ReceiverType.Static(emptyList())
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
