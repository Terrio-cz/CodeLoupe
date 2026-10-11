package codeloupe.tools

import codeloupe.brain.BrainException
import codeloupe.brain.BrainJson
import codeloupe.brain.BrainService
import codeloupe.brain.HygieneReport
import codeloupe.repo.Registry

/** `brain_hygiene`: near-duplicate entries (merge proposals) and anchors whose code is gone or moved, for the nightly retro. Proposals only, nothing is rewritten. */
class BrainHygieneTool(private val brain: BrainService) : Tool {
    override val name = "brain_hygiene"
    override val description = "Shared-memory brain upkeep: entries that say nearly the same (merge proposals), and entries anchored on code that is gone (dangling: supersede or re-anchor) " +
        "or moved (followed by a backfill). Proposals only; record a decision with brain.mjs fact ... --supersedes <id>."
    override val needsRoot = false
    override val properties = Schema.properties("workspace" to Schema.string("the workspace directory holding brain/brain.mjs; default the one you work in, else the only one"))
    override val required = emptyList<String>()

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String = try {
        BrainJson.compact.encodeToString(HygieneReport.serializer(), brain.hygiene(brain.pick(args.string("workspace"), root).path.toString()))
    } catch (e: BrainException) {
        "brain_hygiene failed: ${e.message}. node brain/brain.mjs hygiene answers the duplicates without the daemon."
    }
}
