package codeloupe.cli

import codeloupe.CodeLoupe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A daemon on our port that is not the build we are. It answers every call out of its own index, so a launcher that
 * attaches to it quietly leaves the user with an empty CodeLoupe and no reason for it: on 2026-11-09 a daemon of a
 * version a month old, started from a deploy directory an upgrade had replaced, served the port for over two hours
 * (CL-260). It is never spoken to: `start` replaces it, every other command fails naming it.
 */
class OtherVersion(val version: String, val pid: String, val jar: String?, val runningJobs: Int, private val port: Int) {
    /** What is wrong, for someone who has to decide what to do about it. */
    val what: String get() = "127.0.0.1:$port is served by CodeLoupe $version (pid $pid${jar?.let { ", $it" }.orEmpty()}), not $OURS"

    /** Said by every command but `start`, which is the one that can put it right. */
    val refusal: String get() = "$what: run `codeloupe start` to replace it"

    /** Why a replacement waits: the restart would end the jobs this daemon runs for other windows. Null when it runs none. */
    val jobsRefusal: String?
        get() = runningJobs.takeIf { it > 0 }?.let {
            "$what, and it runs $it job(s) that replacing it would end: wait for them (`codeloupe job status`) " +
                "or replace it anyway with `codeloupe start --force`"
        }

    companion object {
        /** The launcher's own version, as a daemon started from this install reports it. */
        val OURS: String get() = CodeLoupe.VERSION

        /** What a daemon that answered [status] on [port] is, or null when it is our own version. */
        fun of(status: JsonObject, port: Int): OtherVersion? {
            val version = status.text("version")
            if (version == OURS) return null
            return OtherVersion(
                version = version ?: "an unnamed version",
                pid = status.text("pid") ?: "?",
                jar = status.text("jar"),
                runningJobs = status["jobs"]?.jsonObject?.text("running")?.toIntOrNull() ?: 0,
                port = port,
            )
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
    }
}
