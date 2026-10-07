package codeloupe.jobs

import codeloupe.JsonFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Payloads of the job events. The event bus scrubs them; records hold no environment values to begin with. */
object JobEvents {
    fun started(record: JobRecord) = buildJsonObject {
        put("id", record.id)
        put("rootId", record.rootId)
        put("parentId", record.parentId)
        put("tag", record.tag)
        put("command", record.command)
        put("cwd", record.cwd)
        put("slot", record.slot)
        put("startedAt", record.startedAt)
        put("log", record.log)
    }

    /** The record, plus `ok`, `final` (no follow-up job), `wake` (the chain's end should wake the agent) and `text`. */
    fun finished(record: JobRecord, final: Boolean, chain: List<JobRecord>): JsonObject {
        val failed = !record.ok || record.failureBranch
        val wake = final && when (record.wakeOn) {
            Wake.ALWAYS -> true
            Wake.FAILURE -> failed
            Wake.NEVER -> false
        }
        val fields = JsonFormat.json.encodeToJsonElement(JobRecord.serializer(), record).jsonObject - setOf("pid", "pidStart")
        val extra = mapOf(
            "ok" to JsonPrimitive(record.ok), "final" to JsonPrimitive(final), "wake" to JsonPrimitive(wake),
            "text" to JsonPrimitive(JobReport.text(chain)),
        )
        return JsonObject(fields + extra)
    }

    /** A `notify` step: always wakes. */
    fun notify(record: JobRecord, message: String, chain: List<JobRecord>) = buildJsonObject {
        put("id", record.id)
        put("rootId", record.rootId)
        put("tag", record.tag)
        put("message", message.ifEmpty { "job ${record.id} " + if (record.status == JobStatus.DONE) "exit ${record.exit}" else record.status.label })
        put("ok", record.ok)
        put("wake", true)
        put("text", JobReport.text(chain))
    }
}
