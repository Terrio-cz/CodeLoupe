package codeloupe.daemon

import codeloupe.tools.Tool

/**
 * The tools a daemon offers, fixed when it starts: the catalog, and `edit` when [gateOpen] says so at that moment. Clients cache the
 * tool list as a prefix of the model's prompt, so a list that changes under a running session costs the whole session its cache;
 * a verdict of the write gate that arrives later (the `auto` mode works it out in the background) takes effect with the next daemon.
 */
class OfferedTools(base: List<Tool>, edit: Tool, gateOpen: () -> Boolean) {
    val editOffered: Boolean = gateOpen()
    val tools: List<Tool> = if (editOffered) base + edit else base
}
