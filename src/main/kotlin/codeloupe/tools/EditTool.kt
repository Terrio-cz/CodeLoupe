package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.write.WriteRefused
import codeloupe.write.WriteService

/**
 * `edit`: structural writes by declaration name - replace, insert, delete a declaration, add members and imports, create a file,
 * rename a symbol - each guarded by the hash `symbol` printed. One tool for all of them (the tool count is capped), offered only
 * where the write policy allows it (see [codeloupe.write.WriteGate]).
 */
class EditTool(private val writes: WriteService) : Tool {
    override val name = "edit"
    override val description = "Change source by declaration instead of by text: op=replace (name, code, hash: the whole declaration, KDoc and annotations " +
        "included, becomes code), insert_after / insert_before (name = the anchor), insert_member (name = the type, position start|end|after_properties), " +
        "delete (declaration, its lines and a blank line), add_imports (file, imports), create_file (path, code: new files only, package = folder), " +
        "rename (name, to, hash: the declaration, what overrides it and every exact usage, imports included; candidates are listed for you, dry_run=true " +
        "plans only). hash is the hash= of symbol(name): a changed declaration is refused. Code is re-indented and keeps the file's line ends; " +
        "the file must parse as before or nothing is written."
    override val properties = Schema.properties(
        "op" to Schema.enum(OPS),
        "name" to Schema.string("The declaration (Type.member, member(ParamType), pkg.Type, path/File.kt:line), as for symbol"),
        "hash" to Schema.string("hash= printed by symbol for that declaration"),
        "code" to Schema.string("The declaration or member (replace, insert_*, create_file)"),
        "position" to Schema.enum(listOf("start", "end", "after_properties")),
        "to" to Schema.string("rename: the new name"),
        "dry_run" to Schema.boolean("rename: plan only, change nothing"),
        "file" to Schema.string("add_imports: the file"),
        "imports" to Schema.strings("add_imports: names like a.b.C, a.b.*, static a.B.m (Java)"),
        "path" to Schema.string("create_file: the new file, relative to the root"),
    )
    override val required = listOf("op")

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        fun need(key: String) = args.string(key)?.takeIf { it.isNotBlank() } ?: throw WriteRefused("op ${args.string("op")} needs $key")
        return when (val op = args.string("op")) {
            "replace" -> writes.replace(root, need("name"), args.string("hash"), need("code"))
            "insert_after" -> writes.insertAfter(root, need("name"), args.string("hash"), need("code"))
            "insert_before" -> writes.insertBefore(root, need("name"), args.string("hash"), need("code"))
            "insert_member" -> writes.insertMember(root, need("name"), args.string("hash"), need("code"), args.string("position"))
            "delete" -> writes.delete(root, need("name"), args.string("hash"))
            "add_imports" -> writes.addImports(root, need("file"), args.strings("imports"))
            "create_file" -> writes.createFile(root, need("path"), need("code"))
            "rename" -> writes.rename(root, need("name"), need("to"), args.string("hash"), args.bool("dry_run") ?: false)
            else -> throw WriteRefused("op is one of ${OPS.joinToString()}, not $op")
        }
    }

    private companion object {
        val OPS = listOf("replace", "insert_after", "insert_before", "insert_member", "delete", "add_imports", "create_file", "rename")
    }
}
