package codeloupe.secrets.imports

/**
 * The `env` objects of a Claude Code JSON file, wherever they sit: the top level of a settings file, `mcpServers.<name>`
 * of `.mcp.json`, and the same under `projects.<path>` in `~/.claude.json`. Only string members are variables.
 */
object JsonEnvSites {
    class Site(val path: String, val name: String, val start: Int, val end: Int, val value: String) {
        override fun toString() = "$name at $path"
    }

    /** Null when [text] is not JSON. */
    fun find(text: String): List<Site>? {
        val root = JsonSpans.parse(text) ?: return null
        val sites = mutableListOf<Site>()
        walk(text, root, emptyList(), sites)
        return sites
    }

    private fun walk(text: String, node: JsonSpans.Node, path: List<String>, into: MutableList<Site>) {
        if (node !is JsonSpans.Obj) return
        for (member in node.members) {
            val here = path + member.key
            val value = member.value
            if (member.key == "env" && value is JsonSpans.Obj) {
                val where = here.joinToString(" > ")
                value.members.forEach { (key, v) -> if (v is JsonSpans.Str) into += Site("$where > $key", key, v.start, v.end, v.value(text)) }
            } else walk(text, value, here, into)
        }
    }
}
