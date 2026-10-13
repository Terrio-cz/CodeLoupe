package codeloupe.daemon

import java.lang.management.ManagementFactory

/** For the daemon log (CL-251): how long this JVM has run and whether it was given an AOT cache, so a start can be read there. */
internal object JvmStart {
    fun describe(): String {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val cached = runtime.inputArguments.any { it.startsWith("-XX:AOTCache=") }
        return "${runtime.uptime} ms after the JVM started (${if (cached) "AOT cache" else "no AOT cache"})"
    }
}
