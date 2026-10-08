package codeloupe.lang.java

import codeloupe.lang.FileFacts
import codeloupe.lang.LanguageAdapter
import codeloupe.lang.kotlin.Source

class JavaAdapter : LanguageAdapter {
    override val lang = "java"

    private val parser by lazy { JavaParser() }

    override fun owns(path: String): Boolean = path.endsWith(".java", ignoreCase = true)

    override fun extract(path: String, text: String): FileFacts = JavaExtractor(Source(text)).extract(parser.parse(text))
}
