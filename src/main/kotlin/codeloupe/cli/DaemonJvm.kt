package codeloupe.cli

import codeloupe.CodeLoupe
import java.nio.file.Path

/**
 * JVM flags of the background daemon. It answers small queries all day, so it trades peak speed for a small
 * footprint: one GC thread, a small heap, C1 only, and a class-data archive that makes restarts cheap.
 */
object DaemonJvm {
    fun args(home: Path): List<String> = listOf(
        "-Xms16m", "-Xmx96m", "-Xss512k",
        "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m", "-XX:MaxMetaspaceSize=96m",
        "-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=${home.resolve("daemon-${CodeLoupe.VERSION}.jsa")}",
    )
}
