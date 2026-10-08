package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretStore
import codeloupe.secrets.imports.EnvScanner
import codeloupe.secrets.imports.ImportLines
import codeloupe.secrets.imports.InventoryBuilder
import codeloupe.secrets.imports.InventoryReport
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class EnvImportScanCommand : CliktCommand(name = "scan") {
    private val includeExcluded by option("--include-excluded", help = "Also enter folders of excluded systems (TNT/FoodRetailor by default)").flag()
    private val json by option("--json", help = "The report as JSON").flag()

    override fun help(context: Context) =
        "Inventory of the variables in the configured Claude folders and repositories: names, sources, duplicates and conflicts. Never a value."

    override fun run() {
        val home = ConfigLoader.load().home
        val config = ConfigLoader.envImport(home)
        val store = runCatching { SecretStore.open(home) }.getOrNull()
        val report: InventoryReport = InventoryBuilder.build(EnvScanner(config, home, includeExcluded).scan(), config.roots, store)
        if (json) echo(JsonFormat.json.encodeToString(InventoryReport.serializer(), report)) else ImportLines.inventory(report).forEach(::echo)
    }
}
