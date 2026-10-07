package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe webhook …` — persisted subscriptions that POST daemon events to a URL. */
class WebhookCommand : CliktCommand(name = "webhook") {
    init {
        subcommands(WebhookAddCommand(), WebhookListCommand(), WebhookRemoveCommand(), WebhookDeliveriesCommand())
    }

    override fun help(context: Context) =
        "Subscribe a URL to daemon events (job.finished, job.notify, build.done, …): signed, retried, logged."

    override fun run() = Unit
}
