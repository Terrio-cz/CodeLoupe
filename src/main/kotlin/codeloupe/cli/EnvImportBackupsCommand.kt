package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretStore
import codeloupe.secrets.imports.ImportBackups
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.builtins.ListSerializer

class EnvImportBackupsCommand : CliktCommand(name = "backups") {
    private val json by option("--json", help = "As JSON").flag()

    override fun help(context: Context) = "The import backups that can still be rolled back."

    override fun run() {
        val home = ConfigLoader.load().home
        val list = ImportBackups(home.resolve("secrets").resolve("import-backups"), SecretStore.open(home)).list()
        if (json) echo(JsonFormat.json.encodeToString(ListSerializer(ImportBackups.Info.serializer()), list))
        else if (list.isEmpty()) echo("no import backups") else list.forEach { echo("${it.id}  ${it.createdAt}  ${it.files} files") }
    }
}
