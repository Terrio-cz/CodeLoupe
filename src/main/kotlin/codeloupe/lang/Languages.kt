package codeloupe.lang

import codeloupe.lang.java.JavaAdapter
import codeloupe.lang.kotlin.KotlinAdapter

/** File extension -> adapter. Adding a language = one entry here. */
object Languages {
    private val adapters: List<LanguageAdapter> = listOf(KotlinAdapter(), JavaAdapter())

    fun languageOf(path: String): String? = adapterFor(path)?.lang

    fun extract(path: String, text: String): FileFacts? = adapterFor(path)?.extract(path, text)

    private fun adapterFor(path: String): LanguageAdapter? = adapters.firstOrNull { it.owns(path) }
}
