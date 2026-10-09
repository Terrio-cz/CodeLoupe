package codeloupe.lang.java

import codeloupe.lang.FileFacts
import codeloupe.lang.LanguageAdapter
import codeloupe.lang.kotlin.Source

class JavaAdapter : LanguageAdapter {
    override val lang = "java"

    private val parser by lazy { JavaParser() }

    override fun owns(path: String): Boolean = path.endsWith(".java", ignoreCase = true)

    override fun extract(path: String, text: String): FileFacts = JavaExtractor(Source(text), stemOf(path)).extract(parser.parse(text))

    // An implicitly declared class is named after its file (JEP 512).
    private fun stemOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
}
