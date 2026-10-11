package codeloupe.cli

import codeloupe.conventions.ConventionCards
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import java.nio.file.Path

/** Local and read-only: builds the cards from the sources on the disk, no daemon, no index. */
class ConventionsCommand : CliktCommand(name = "conventions") {
    private val root by option("--root", help = "Repository or worktree to read").default(Path.of("").toAbsolutePath().toString())
    private val module by option("--module", help = "A module by name (:public-api); repeat it").multiple()
    private val forPath by option("--for", help = "The modules that own this repository-relative path; repeat it").multiple()
    private val largest by option(help = "The N largest modules by main source lines").int()
    private val list by option("--list", help = "Only list the modules with their main source lines").flag()

    override fun help(context: Context) = "A card of a module's conventions (naming, layout, errors, tests), counted from its sources; at most 1.5 kB, no model."

    override fun run() {
        val base = Path.of(root).toAbsolutePath().normalize()
        val sized = ConventionCards.largest(base)
        if (list) return sized.forEach { echo("${it.first.name}  ${it.second} lines") }
        val modules = (module.mapNotNull { name -> sized.firstOrNull { it.first.name == name }?.first ?: throw UsageError("no module $name; try --list") } +
            ConventionCards.owning(base, forPath) + sized.take(largest ?: 0).map { it.first }).distinct()
        if (modules.isEmpty()) throw UsageError("name a module with --module, --for or --largest; --list shows the modules")
        echo(modules.joinToString("\n\n") { ConventionCards.card(it) })
    }
}
