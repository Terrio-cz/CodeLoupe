package codeloupe.cli

/**
 * JVM flags of the background daemon. It answers small queries all day, so it trades peak speed for a small
 * footprint: one GC thread, a small heap, C1 only. No dynamic class-data archive: one dumped after a parse holds
 * the parser's classes and maps them into every later run (+30 MB resident) for no measurable start-up gain.
 *
 * Resident memory after a mixed query load (CL-118, `tools/rss-mix.mjs`): compact object headers shrink every object
 * of the cached rows by a quarter, a 10 MB young generation stops the eden from being touched to its last page, and
 * a tight free ratio lets the old generation give pages back after a full collection. Together ~10 MB less than
 * the defaults on TerrioImporter. With a parse worker (CL-125, the default) the compiler's parser never loads here, no
 * parse fills the heap with syntax trees, and the heap limit is 64 MB; a daemon that parses in its own process keeps 80.
 */
object DaemonJvm {
    const val HEAP_MB = 64
    const val HEAP_MB_PARSING = 80

    /** [parsesHere]: the daemon parses edited files itself (`parseWorkerIdleSeconds` 0). */
    fun args(parsesHere: Boolean = false): List<String> = listOf(
        "-Xms16m", "-Xmx${if (parsesHere) HEAP_MB_PARSING else HEAP_MB}m", "-Xmn10m", "-Xss512k",
        "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
        "-XX:+UseCompactObjectHeaders", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m", "-XX:MaxMetaspaceSize=96m",
    )
}
