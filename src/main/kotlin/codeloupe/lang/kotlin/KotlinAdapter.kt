package codeloupe.lang.kotlin

import codeloupe.lang.FileFacts
import codeloupe.lang.LanguageAdapter

class KotlinAdapter : LanguageAdapter {
    override val lang = "kotlin"

    private val parser by lazy { KotlinParser() }

    override fun owns(path: String): Boolean = EXTENSION.containsMatchIn(path)

    override fun extract(path: String, text: String): FileFacts = KotlinExtractor(Source(text)).extract(parser.parse(path, text))

    private companion object {
        val EXTENSION = Regex("\\.kts?$", RegexOption.IGNORE_CASE)
    }
}
