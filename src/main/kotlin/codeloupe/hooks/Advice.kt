package codeloupe.hooks

/**
 * The CodeLoupe call that answers what a shell command or a read was after. [why] names the kind for the counters;
 * [strong] when the command plainly concerns source files, so that `redirect` may refuse it.
 */
data class Advice(val tool: String, val arguments: List<Pair<String, Any>>, val why: String, val strong: Boolean, val note: String = "") {
    /** The MCP form: `usages name="OrderService"`. */
    fun call(): String = (listOf(tool) + arguments.map { (key, value) -> if (value is String) "$key=\"${value.replace("\"", "\\\"")}\"" else "$key=$value" }).joinToString(" ")

    /** The shell form: `codeloupe usages OrderService`. */
    fun cli(): String {
        val main = arguments.firstOrNull { it.second is String }?.second as? String
        val flags = arguments.filter { it.second == true }.joinToString("") { " --" + it.first.replace(Regex("[A-Z]")) { m -> "-" + m.value.lowercase() } }
        val kind = arguments.firstOrNull { it.first == "kind" }?.let { " --kind ${it.second}" }.orEmpty()
        return "codeloupe $tool" + (main?.let { " " + quote(it) }.orEmpty()) + kind + flags
    }

    private fun quote(text: String) = if (text.any { !it.isLetterOrDigit() && it !in "_./" }) "'" + text.replace("'", "'\\''") + "'" else text
}
