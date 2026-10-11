package codeloupe.tools

import codeloupe.brain.BrainNotes
import codeloupe.repo.Registry
import kotlinx.coroutines.CancellationException

/** A `symbol` or `context` that adds, per declaration it answers for, one line with the brain entries anchored on it. The answer itself is unchanged. */
class BrainAnnotatedTool(private val inner: Tool, private val notes: BrainNotes) : Tool by inner {
    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val text = inner.answer(registry, root, args)
        val name = args.string("name")?.trim().orEmpty()
        if (name.isEmpty() || text.startsWith("no declaration")) return text
        val lines = try {
            notes.lines(root, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return if (lines.isEmpty()) text else text + "\n" + lines.joinToString("\n")
    }
}
