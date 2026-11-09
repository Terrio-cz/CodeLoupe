package codeloupe.platform

import java.nio.file.Path

/**
 * Where the running code was loaded from. A version alone does not say which install answers on a port: two deploys of
 * the same project sit side by side, and until the daemon names its own jar that could only be read out of the operating
 * system's process table (CL-260).
 */
object CodeSource {
    /** The jar or classes directory [type] came from, or null where the JVM does not say (a class loaded without one). */
    fun of(type: Class<*>): String? = runCatching {
        type.protectionDomain?.codeSource?.location?.toURI()?.let { Path.of(it).toString() }
    }.getOrNull()
}
