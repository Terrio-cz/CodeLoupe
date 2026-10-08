package codeloupe.uiapi

import codeloupe.config.Config
import codeloupe.tracker.Trackers

/** The Settings screen: what the daemon runs with. Token values are never read out, only whether one resolves. */
internal class SettingsViews(private val config: Config, private val catalog: RepoCatalog, private val trackers: Trackers, private val pollSec: Long) {
    suspend fun settings() = SettingsView(
        port = config.port,
        home = config.home.toString(),
        configFile = config.home.resolve("config.json").toString(),
        defaultRoot = config.defaultRoot,
        repos = catalog.repos().map { SettingsView.Repo(it.id, it.path, it.defaultRef) },
        youtrack = trackers.mirrors.map { m ->
            SettingsView.Youtrack(m.instance.url, m.instance.projects, runCatching { m.instance.token.read().isNotBlank() }.getOrDefault(false), pollSec)
        },
        budgets = with(config.budgets) { SettingsView.Budgets(dailyWeighted, rssMb, IndexViews.BUILD_PEAK_BUDGET_MB, p95Ms, queueWaitMs, busyRate) },
    )
}
