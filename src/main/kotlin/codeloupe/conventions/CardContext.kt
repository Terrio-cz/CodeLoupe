package codeloupe.conventions

/** The files of one module, split into main and test sources, and the package its main sources share. */
class CardContext(val files: List<ModuleFile>) {
    val main: List<ModuleFile> = files.filter { !it.test }
    val tests: List<ModuleFile> = files.filter { it.test }

    /** The longest package prefix every main file lives under; empty when the sources share none. */
    val basePackage: String by lazy {
        val packages = main.map { it.packageName.split('.').filter(String::isNotEmpty) }.filter { it.isNotEmpty() }
        if (packages.isEmpty()) "" else packages.reduce { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }.joinToString(".")
    }
}
