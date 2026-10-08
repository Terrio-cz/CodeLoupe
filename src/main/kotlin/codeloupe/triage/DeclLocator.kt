package codeloupe.triage

/** Finds the declaration that holds a place named by a build or test output. */
interface DeclLocator {
    /** The declaration around [line] of the file [path] as the output spelled it (relative, absolute or by file name); null when unknown. */
    fun at(path: String, line: Int): DeclPointer?

    /** The declaration around [line] of [file] in the class [className] of a stack frame (`pkg.Outer$Inner`); null when unknown. */
    fun inFrame(className: String, file: String, line: Int): DeclPointer?

    /** Whether [path] is a test source. */
    fun isTest(path: String): Boolean
}
