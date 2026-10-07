package codeloupe.index

/** `importers/ruian/addresses/src/main/kotlin/…` -> module `importers/ruian/addresses`, source set `main`. */
data class ModulePath(val module: String, val sourceSet: String) {
    companion object {
        fun of(path: String): ModulePath {
            val parts = path.split('/')
            val src = parts.indexOf("src")
            if (src < 0) return ModulePath("", "")
            return ModulePath(parts.subList(0, src).joinToString("/"), parts.getOrElse(src + 1) { "" })
        }
    }
}
