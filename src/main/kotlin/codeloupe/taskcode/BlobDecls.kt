package codeloupe.taskcode

import codeloupe.changes.DeclChange
import codeloupe.changes.DeclDiff
import codeloupe.changes.DeclVersion
import codeloupe.git.BlobSource
import codeloupe.index.Extraction
import codeloupe.index.ModulePath
import codeloupe.index.ParamsJson
import codeloupe.lang.FileFacts
import codeloupe.lang.Languages
import codeloupe.query.DeclRow

/**
 * The declarations one commit changed, from the blobs on both sides of each of its source files: the same matching
 * as `changes`, without a store. Parsed one file at a time; a commit with more source files than the daemon
 * parses inline gets none (files only).
 */
object BlobDecls {
    const val MAX_FILES = 200

    /** Null when the commit has too many source files to parse here. */
    fun of(files: List<CommitFile>, blobs: BlobSource): List<CommitDecl>? {
        val sources = files.filter { Languages.languageOf(it.path) != null }
        if (sources.size > MAX_FILES) return null
        val texts = HashMap<String, String>()
        blobs.read(sources.flatMap { listOfNotNull(it.oldBlob, it.newBlob) }.distinct()) { sha, text -> texts[sha] = text }
        return sources.flatMap { file ->
            val before = file.oldBlob?.let { texts[it] }?.let { versions(file.path, it) }.orEmpty()
            val after = file.newBlob?.let { texts[it] }?.let { versions(file.path, it) }.orEmpty()
            DeclDiff.of(before, after).sortedBy { it.current.row.startLine }.let(::collapse).map { change ->
                val row = change.current.row
                CommitDecl(file.path, change.mark, row.kind, row.container, row.name, row.sig, row.startLine, row.endLine)
            }
        }
    }

    /** The members of an added or removed type went with it: they are not changes of their own. */
    private fun collapse(changes: List<DeclChange>): List<DeclChange> {
        val byRow = changes.filter { it.mark == DeclChange.ADDED || it.mark == DeclChange.REMOVED }.associateBy { it.mark to it.current.row.id }
        return changes.filter { change ->
            var parent = change.current.row.parentId
            var hidden = false
            while (parent != null && !hidden) {
                val outer = byRow[change.mark to parent] ?: break
                hidden = true
                parent = outer.current.row.parentId
            }
            !hidden
        }
    }

    private fun versions(path: String, text: String): List<DeclVersion> = DeclVersion.of(rows(path, Extraction.extract(path, text)), text)

    /** Rows as the store would hold them; ids are positions in the file's list, which is all the diff needs. */
    fun rows(path: String, facts: FileFacts): List<DeclRow> {
        val module = ModulePath.of(path)
        return facts.decls.mapIndexed { i, d ->
            DeclRow(
                id = i.toLong(), kind = d.kind, name = d.name, container = d.container,
                fqn = listOf(facts.packageName, d.container, d.name).filter { it.isNotEmpty() }.joinToString("."),
                receiver = d.receiver, params = ParamsJson.of(d.params), paramCount = d.params.size, returns = d.returns,
                modifiers = d.modifiers.joinToString(" "), supertypes = d.supertypes.joinToString(" "),
                startLine = d.start, declLine = d.declStart, endLine = d.end, sig = d.sig, hash = d.hash, local = d.local,
                parentId = d.parent.takeIf { it >= 0 }?.toLong(), path = path, module = module.module, sourceSet = module.sourceSet, src = "blob",
            )
        }
    }
}
