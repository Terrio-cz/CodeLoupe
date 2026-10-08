package codeloupe.uiapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the app turns into notifications; [epoch] changes when the daemon numbers events anew. */
@Serializable
data class EventsView(val epoch: String, val lastSeq: Long, val items: List<Item>) {
    @Serializable
    enum class Kind {
        @SerialName("budget_breach") BUDGET_BREACH,
        @SerialName("build_finished") BUILD_FINISHED,
        @SerialName("build_failed") BUILD_FAILED,
        @SerialName("gap_new") GAP_NEW,
    }

    @Serializable
    enum class Severity {
        @SerialName("info") INFO,
        @SerialName("warning") WARNING,
        @SerialName("critical") CRITICAL,
    }

    @Serializable
    data class Item(val seq: Long, val at: String, val kind: Kind, val severity: Severity, val title: String, val body: String, val ref: Ref)

    @Serializable
    data class Ref(val screen: String, val id: String?)
}
