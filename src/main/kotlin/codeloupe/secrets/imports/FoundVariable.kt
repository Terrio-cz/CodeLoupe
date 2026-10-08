package codeloupe.secrets.imports

import codeloupe.secrets.SecretScope
import java.nio.file.Path

/**
 * One variable as a source file holds it. The [value] lives only in memory for the duration of a scan or import, and
 * [toString] never shows it, so a log line or an exception that names a variable cannot leak it.
 */
class FoundVariable(
    val name: String,
    val file: Path,
    val kind: SourceKind,
    /** Where the variable would apply by default: the folder it was found in decides. */
    val scope: SecretScope,
    /** Where in the file: a line for a dotenv file, the JSON path of the env object for a JSON file. */
    val locator: String,
    /** Offsets of the text a replacement overwrites (the statement or the string literal). */
    val start: Int,
    val end: Int,
    val value: String,
) {
    val sensitive: Boolean get() = Sensitivity.of(name, value)

    val reference: Boolean get() = SourceRewriter.isReference(value)

    /** Stable between scans and free of the value, so a confirmation can name an occurrence. */
    val id: String = ValueFingerprint.id(file.toString(), locator, name)

    override fun toString() = "$name in $file ($locator)"
}
