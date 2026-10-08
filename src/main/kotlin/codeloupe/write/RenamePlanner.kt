package codeloupe.write

import codeloupe.query.DeclRow
import codeloupe.query.Format
import codeloupe.query.Resolver
import codeloupe.query.View
import codeloupe.query.usages.Kinds
import codeloupe.query.usages.Label
import codeloupe.query.usages.Targets
import codeloupe.query.usages.UsageFinder

/**
 * Plans the rename of one symbol from the index: the declaration and everything that has to carry its name with it (what it
 * overrides, what overrides it, a class with its constructors), every usage the index is sure of, the imports of it. A use the
 * index is not sure of is not renamed but listed. Refuses what it cannot do right: a name that exists already, an override
 * of a member outside the index, conventions the language reads by name.
 */
internal class RenamePlanner(private val view: View) {
    fun plan(name: String, newName: String): RenamePlan {
        validate(newName)
        val targets = Resolver.resolve(view, name)
        if (targets.isEmpty()) throw WriteRefused("no declaration \"$name\"")
        if (!Targets.oneSymbol(targets)) {
            throw WriteRefused("${targets.size} declarations match \"$name\" - qualify it (Type.member, pkg.Type):\n" + targets.take(LISTED).joinToString("\n", transform = Format::head))
        }
        val oldName = targets.first().name
        if (oldName == newName) throw WriteRefused("$newName is the name already")
        val finder = UsageFinder(view)
        val family = family(targets, finder)
        targets.firstOrNull { it.local }?.let { throw WriteRefused("${it.name} is local to code: rename it by hand") }
        family.forEach { refuseSpecial(it, finder, family) }
        family.forEach { refuseClash(it, newName) }
        val usages = finder.usages(family).filter { it.ref.name == oldName }
        val sites = ArrayList<RenameSite>()
        val candidates = ArrayList<RenameCandidate>()
        for (usage in usages) {
            val ref = usage.ref
            val site = RenameSite(ref.path, ref.line, ref.col)
            when {
                usage.label == Label.EXACT -> sites += site
                ref.kind == "named_arg" && usage.label == Label.CANDIDATE && namesItsOwner(ref.recv, family) -> sites += site
                usage.label == Label.CANDIDATE && finder.denotesOnly(ref, family) -> sites += site
                usage.label == Label.CANDIDATE -> candidates += RenameCandidate(ref.path, ref.line, "may be a use")
            }
        }
        candidates += accessors(family, finder)
        refuseCapture(sites, newName, finder)
        val imports = family.flatMap { d -> view.imports("i.fqn = :fqn AND i.star = 0", mapOf("fqn" to d.fqn)).map { it.path to d.fqn } }
            .distinct().groupBy({ it.first }, { it.second })
        val shared = family.map { it.fqn }.filter { fqn -> view.decls("d.fqn = :fqn AND d.local = 0", mapOf("fqn" to fqn)).any { it !in family } }.toSet()
        val others = view.decls("d.name = :n AND d.local = 0", mapOf("n" to oldName)).filter { it !in family }.take(MAX_OTHERS)
        val othersExact = if (others.isEmpty()) emptyMap() else exactLines(finder.usages(others), others)
        return RenamePlan(
            oldName, newName, targets, family, sites.distinct(), imports, candidates.distinctBy { it.path to it.line }, warnings(family, finder),
            fileMove(family, newName), shared, others, othersExact,
        )
    }

    // Lines per file where the index is sure the declarations are used.
    private fun exactLines(usages: List<codeloupe.query.usages.Usage>, decls: List<DeclRow>): Map<String, Int> =
        usages.filter { it.label == Label.EXACT && it.ref.name == decls.first().name }.groupBy { it.ref.path }.mapValues { (_, hits) -> hits.map { it.ref.line }.distinct().size }

    // The target, what it overrides and what overrides it, until nothing more joins.
    private fun family(targets: List<DeclRow>, finder: UsageFinder): List<DeclRow> {
        val overrides = finder.context.overrides
        val seen = LinkedHashSet(targets)
        val queue = ArrayDeque(targets)
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            for (related in overrides.overridden(d) + overrides.overriding(d)) if (seen.add(related)) queue.add(related)
        }
        // A class carries its constructors' names (Java), which are its own.
        seen += seen.filter { it.kind in Kinds.TYPES }.flatMap { type -> finder.cache.children(type).filter { it.kind == "constructor" && it.name == type.name } }
        return seen.toList()
    }

    private fun refuseSpecial(d: DeclRow, finder: UsageFinder, family: List<DeclRow>) {
        val modifiers = d.modifiers.split(' ')
        if ("operator" in modifiers || d.name in CONVENTIONS) throw WriteRefused("${d.name} is read by its name (operator, convention or entry point): rename it by hand")
        val overridesOne = finder.context.overrides.overridden(d).isNotEmpty() || family.any { it != d && d in finder.context.overrides.overriding(it) }
        if ("override" in modifiers && !overridesOne) {
            throw WriteRefused("${d.fqn} overrides a member outside the index: renaming it would break the override")
        }
        if (d.kind == "enum_entry" && d.container.isEmpty()) throw WriteRefused("${d.name} has no enum")
    }

    private fun validate(newName: String) {
        if (!IDENTIFIER.matches(newName)) throw WriteRefused("$newName is not a plain identifier")
        if (newName in KEYWORDS) throw WriteRefused("$newName is a keyword")
    }

    // A declaration of the new name in the same place, or in the scope of a use.
    private fun refuseClash(d: DeclRow, newName: String) {
        val kinds = if (d.kind in Kinds.TYPES) Kinds.TYPES else setOf(d.kind)
        val same = view.decls("d.name = :n AND d.container = :c AND d.local = 0", mapOf("n" to newName, "c" to d.container))
            .filter { it.kind in kinds && it.fqn.substringBeforeLast('.') == d.fqn.substringBeforeLast('.') }
            .filter { d.kind != "fun" || it.paramCount == d.paramCount }
        same.firstOrNull { it.path == d.path || d.container.isEmpty() }?.let { throw WriteRefused("${it.fqn} exists already (${Format.head(it)})") }
    }

    // A local of the new name where a renamed use stands would capture it.
    private fun refuseCapture(sites: List<RenameSite>, newName: String, finder: UsageFinder) {
        val cache = finder.cache
        val bound = cache.refsNamed(newName).filter { it.bind != null || it.kind == "named_arg" }
        val locals = cache.named(newName).filter { it.local }
        if (bound.isEmpty() && locals.isEmpty()) return
        for (site in sites) {
            val file = cache.file(site.path) ?: continue
            val use = cache.refAt(site.path, site.line, site.col) ?: continue
            // Only a name written without a receiver can be taken by a local of the same name.
            if (use.recv != null) continue
            val chain = file.chain(file.decl(use.declId)).map { it.id }.toSet()
            val captured = bound.any { it.path == site.path && it.declId in chain } || locals.any { it.path == site.path && it.parentId in chain }
            if (captured) throw WriteRefused("${site.path}:${site.line} has a local or parameter named $newName that would capture the renamed use")
        }
    }

    // The callee of a named argument is the class the renamed property belongs to.
    private fun namesItsOwner(callee: String?, family: List<DeclRow>): Boolean {
        if (callee == null) return false
        val owners = family.filter { it.kind == "property" && it.container.isNotEmpty() }.map { it.container.substringAfterLast('.') }.toSet()
        if (callee !in owners) return false
        return view.decls("d.name = :n AND d.kind IN ('class', 'enum', 'interface', 'object')", mapOf("n" to callee)).size == 1
    }

    // Java accessors of a Kotlin property and Kotlin property access to a Java accessor: not followed, so listed.
    private fun accessors(family: List<DeclRow>, finder: UsageFinder): List<RenameCandidate> = family.flatMap { d ->
        val capital = d.name.replaceFirstChar { it.uppercase() }
        when {
            d.kind == "property" && d.path.endsWith(".kt") ->
                listOf("get$capital", "set$capital", "is$capital").flatMap { finder.cache.refsNamed(it) }.filter { it.path.endsWith(".java") }
                    .map { RenameCandidate(it.path, it.line, "Java accessor of the Kotlin property ${d.name}") }
            d.kind == "fun" && d.path.endsWith(".java") && ACCESSOR.matches(d.name) -> {
                val property = d.name.replace(ACCESSOR, "$2").replaceFirstChar { it.lowercase() }
                finder.cache.refsNamed(property).filter { it.path.endsWith(".kt") && it.kind in setOf("nav", "name") }
                    .map { RenameCandidate(it.path, it.line, "Kotlin property access to the Java accessor ${d.name}") }
            }
            else -> emptyList()
        }
    }

    private fun warnings(family: List<DeclRow>, finder: UsageFinder): List<String> {
        val out = ArrayList<String>()
        val types = finder.context.types
        for (d in family) {
            val owner = finder.cache.parent(d)
            if (owner != null && owner.modifiers.split(' ').any { m -> WIRE.any { m.contains(it) } } || d.modifiers.split(' ').any { m -> WIRE.any { m.contains(it) } }) {
                out += "${d.fqn} is annotated for serialisation or persistence: the name it has outside the code may change with it"
            }
            if (owner != null && Kinds.CLASSIFIERS.contains(owner.kind) && d.kind == "fun" && types.closure(owner).external.isNotEmpty() && "override" !in d.modifiers.split(' ')) {
                out += "${owner.name} extends types outside the index (${types.closure(owner).external.take(3).joinToString()}): if ${d.name} implements one of their members, the rename breaks it"
            }
        }
        return out.distinct()
    }

    // A public Java type lives in the file of its name.
    private fun fileMove(family: List<DeclRow>, newName: String): Pair<String, String>? {
        val type = family.firstOrNull { it.kind in Kinds.TYPES && it.container.isEmpty() && it.path.endsWith(".java") && it.path.substringAfterLast('/') == "${it.name}.java" } ?: return null
        val dir = type.path.substringBeforeLast('/', "")
        return type.path to (if (dir.isEmpty()) "$newName.java" else "$dir/$newName.java")
    }

    private companion object {
        const val LISTED = 10
        const val MAX_OTHERS = 40
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val ACCESSOR = Regex("^(get|is)([A-Z].*)$")
        val CONVENTIONS = setOf(
            "toString", "equals", "hashCode", "compareTo", "main", "finalize", "clone", "invoke", "iterator", "hasNext", "next", "getValue", "setValue",
            "provideDelegate", "close", "component1", "component2", "component3", "component4", "component5", "copy",
        )
        val WIRE = listOf("Serializable", "Json", "SerialName", "Entity", "Column", "Table", "XmlElement", "Document", "SerializedName")
        val KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package", "return", "super",
            "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while", "abstract", "assert", "boolean", "byte", "case", "catch", "char",
            "const", "default", "double", "enum", "extends", "final", "finally", "float", "goto", "implements", "import", "instanceof", "int", "long", "native",
            "new", "private", "protected", "public", "short", "static", "strictfp", "switch", "synchronized", "throws", "transient", "void", "volatile",
        )
    }
}
