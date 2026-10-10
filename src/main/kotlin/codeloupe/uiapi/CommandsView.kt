package codeloupe.uiapi

import codeloupe.commands.CommandReport
import codeloupe.ingest.IngestStatus
import kotlinx.serialization.Serializable

/** The Commands tab of the Runs screen (`GET /ui-api/v1/commands`): the report of `codeloupe metrics commands`, the roles to filter by, and how far the transcript ingest is. */
@Serializable
data class CommandsView(val report: CommandReport, val roles: List<String>, val ingest: IngestStatus)
