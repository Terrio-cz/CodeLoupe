package codeloupe.brain

import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.Resolver
import codeloupe.query.View
import codeloupe.repo.Registry
import java.nio.file.Files
import java.nio.file.Path

/** Turns the `file:line` a finding cites into anchors of the enclosing declaration, file and module. */
class BrainAnchors(private val registry: Registry) {
    suspend fun resolve(codeRoot: String, refs: List<LineRef>, trusted: Boolean): List<ResolvedRef> {
        if (refs.isEmpty()) return emptyList()
        val root = Path.of(registry.locate(codeRoot).worktree)
        return registry.query(codeRoot, speculative = false) { view -> refs.map { one(view, root, it, trusted) } }
    }

    private fun one(view: View, root: Path, ref: LineRef, trusted: Boolean): ResolvedRef {
        val path = pathOf(view, root, ref.file) ?: return ResolvedRef(ref.file, ref.line, emptyList())
        val decl = DeclAnchors.enclosing(view, path, ref.line)?.let { anchored(view, it, ref.text, trusted) }
        val module = decl?.module?.takeIf { it.isNotEmpty() } ?: ModulePath.of(path).module
        val anchors = listOfNotNull(
            decl?.let { DeclAnchors.sym(view, it) },
            "${IndexedEntry.FILE}$path",
            module.takeIf { it.isNotEmpty() }?.let { "${IndexedEntry.MOD}$it" },
        )
        return ResolvedRef(ref.file, ref.line, anchors)
    }

    /**
     * The declaration a citation is anchored to. The writer's own worktree: the one at the line. Otherwise (lines drift) the one at the line only where the text
     * names it, else the type it sits in where the text names that, else none: the file and module anchors still hold.
     */
    private fun anchored(view: View, decl: DeclRow, text: String?, trusted: Boolean): DeclRow? {
        if (DeclAnchors.anchorable(decl) && (trusted || namesMember(text, decl))) return decl
        val owner = view.decls("d.fqn = :fqn AND d.local = 0", mapOf("fqn" to decl.fqn.removeSuffix(".${decl.name}")))
            .filter { it.kind in TYPES }.singleOrNull() ?: return null
        return owner.takeIf { DeclAnchors.anchorable(it) && (trusted || namesType(text, it)) }
    }

    /** The indexed path of a cited file: exact, by its tail (`a/.../File.kt` elides directories), or, for a file the index does not hold, on disk. */
    private fun pathOf(view: View, root: Path, cited: String): String? {
        val raw = cited.replace('\\', '/').removePrefix("./")
        if ("..." in raw) {
            val head = raw.substringBefore("...").trimEnd('/')
            val tail = raw.substringAfterLast("...").trimStart('/')
            return view.filesBySuffix("/$tail").filter { head.isEmpty() || it.startsWith("$head/") }.distinct().singleOrNull()
        }
        return Resolver.resolvePath(view, raw) ?: raw.takeIf { runCatching { Files.isRegularFile(root.resolve(it)) }.getOrDefault(false) }
    }

    companion object {
        private val TYPES = setOf("class", "interface", "object", "enum", "annotation")

        /**
         * Does the text name the declaration? Lines drift, a name does not. A name counts when it reads as code and not as a word: a CamelCase or snake_case
         * name, one written in backticks, called (`name(`) or reached (`.name`); a plain lowercase word such as "address" does not.
         */
        fun namesMember(text: String?, decl: DeclRow): Boolean = text != null && decl.name.length >= MIN_NAME && mentionsAsCode(text, decl.name)

        /** Does the text name the type: by a CamelCase name, or as code? */
        fun namesType(text: String?, type: DeclRow): Boolean = text != null && type.name.length >= MIN_TYPE && (HUMP.containsMatchIn(type.name) && mentions(text, type.name) || mentionsAsCode(text, type.name))

        private fun mentions(text: String, name: String) = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])").containsMatchIn(text)

        private fun mentionsAsCode(text: String, name: String): Boolean {
            if (!mentions(text, name)) return false
            if (HUMP.containsMatchIn(name) || '_' in name) return true
            val q = Regex.escape(name)
            return Regex("`$q`|(?<![A-Za-z0-9_])$q\\(|\\.$q(?![A-Za-z0-9_])").containsMatchIn(text)
        }

        private val HUMP = Regex("[a-z0-9][A-Z]")
        private const val MIN_NAME = 4
        private const val MIN_TYPE = 6
    }
}
