package codeloupe.reconcile

import codeloupe.JsonFormat
import codeloupe.daemon.AppendLog
import codeloupe.events.EventBus
import codeloupe.events.EventTypes
import codeloupe.platform.IsoTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Every attempt of the reconciler, in the daemon log, in `reconcile.jsonl` and as a `reconcile.action` event. */
class ReconcileRecorder(private val log: (String) -> Unit, private val journal: AppendLog, private val bus: EventBus) {
    operator fun invoke(result: ActionResult, trigger: String) {
        log("reconcile $trigger ${result.outcome.name.lowercase()} ${result.kind.name.lowercase()} ${result.name}${result.workspace?.let { " ($it)" }.orEmpty()} ${result.detail}".trimEnd())
        val line = buildJsonObject {
            put("at", IsoTime.now())
            put("trigger", trigger)
            put("result", JsonFormat.json.encodeToJsonElement(ActionResult.serializer(), result))
        }
        journal.append(line.toString())
        runCatching {
            bus.emit(
                EventTypes.RECONCILE_ACTION,
                buildJsonObject {
                    put("trigger", trigger)
                    put("key", result.key)
                    put("kind", result.kind.name.lowercase())
                    put("name", result.name)
                    result.workspace?.let { put("workspace", it) }
                    put("outcome", result.outcome.name.lowercase())
                    put("detail", result.detail)
                },
            )
        }
    }
}
