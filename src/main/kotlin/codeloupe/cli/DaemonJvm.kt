package codeloupe.cli

/**
 * JVM flags of the background daemon. It answers small queries all day, so it trades peak speed for a small
 * footprint: one GC thread, a small heap, C1 only. No dynamic class-data archive: one dumped after a parse holds
 * the parser's classes and maps them into every later run (+30 MB resident) for no measurable start-up gain.
 */
object DaemonJvm {
    fun args(): List<String> = listOf(
        "-Xms16m", "-Xmx80m", "-Xss512k",
        "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m", "-XX:MaxMetaspaceSize=96m",
    )
}
