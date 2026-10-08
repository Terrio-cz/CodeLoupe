package codeloupe.metrics

import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/** Finds the code files a run created (a `Write` the tool answered with "File created") and measures their skeleton. */
object BoilerplateAnalyzer {
    private val CODE_FILE = Regex("""(?i)\.(kt|kts|java)$""")
    private val TEST_FILE = Regex("""(?i)([\\/]src[\\/]test[\\/]|[\\/]tests?[\\/]|Test\.(kt|java)$)""")
    private val SKELETON = listOf(
        Regex("""^\s*(package|import)\s"""),
        Regex("""^\s*@[\w.]+(\(.*\))?\s*$"""),
        Regex("""^\s*[})\],;]+\s*$"""),
        Regex("""^\s*((public|private|internal|protected|data|sealed|enum|abstract|open|value|annotation|inner|companion|fun)\s+)*(class|object|interface)\b.*[{(]?\s*$"""),
    )

    fun newFiles(run: Run): List<NewFile> = run.tools.filter { it.name == "Write" && it.head.startsWith("File created") }.mapNotNull { call ->
        val path = call.file ?: return@mapNotNull null
        val text = (call.input["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        if (!CODE_FILE.containsMatchIn(path)) return@mapNotNull null
        val lines = text.removeSuffix("\n").split('\n')
        val skeleton = lines.filter(::isSkeleton).sumOf { it.length + 1 }.toLong().coerceAtMost(text.length.toLong())
        val after = maxOf(0, run.turns - call.turn)
        // Written once as output, then kept in the context for the turns that follow.
        val cost = floor(text.length / 4.0 * (OUTPUT + Usage.WRITE_1H + Usage.READ * after) + 0.5).toLong()
        NewFile(path, if (TEST_FILE.containsMatchIn(path)) "test" else "main", BoilerplateShare(1, text.length.toLong(), skeleton, cost, cost * skeleton / maxOf(1, text.length)))
    }

    private fun isSkeleton(line: String): Boolean = line.isBlank() || SKELETON.any { it.containsMatchIn(line) }

    private const val OUTPUT = 5.0
}
