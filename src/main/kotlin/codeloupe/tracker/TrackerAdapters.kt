package codeloupe.tracker

import codeloupe.tracker.youtrack.JdkTransport
import codeloupe.tracker.youtrack.YouTrackAdapter

/** The adapter for a configured instance, by its `type`; null for a type CodeLoupe does not know. */
object TrackerAdapters {
    fun create(instance: TrackerInstance): TrackerAdapter? = when (instance.type) {
        "youtrack" -> YouTrackAdapter(JdkTransport(instance.url, instance.token))
        else -> null
    }
}
