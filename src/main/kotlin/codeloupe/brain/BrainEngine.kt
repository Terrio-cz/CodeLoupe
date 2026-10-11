package codeloupe.brain

import java.nio.file.Path

/** A workspace's `brain/brain.mjs`, run with arguments: the one door to the brain files. [BrainCli] runs the real script; tests give a canned one. */
interface BrainEngine {
    /** Standard output of `brain.mjs <args>` in [root] (the workspace directory) with [env] added to the environment. */
    suspend fun run(root: Path, args: List<String>, env: Map<String, String> = emptyMap()): String
}
