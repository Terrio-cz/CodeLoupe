package codeloupe.brain

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** A `brain.mjs` that prints what the test says and remembers how it was asked. */
class CannedEngine(@Volatile var export: String = "{}") : BrainEngine {
    val calls = CopyOnWriteArrayList<List<String>>()
    val environments = CopyOnWriteArrayList<Map<String, String>>()
    val answers = HashMap<String, String>()

    override suspend fun run(root: Path, args: List<String>, env: Map<String, String>): String {
        calls += args
        environments += env
        return if (args.firstOrNull() == "export") export else answers[args.firstOrNull()].orEmpty()
    }
}
