package codeloupe.jobs

import codeloupe.config.ConfigFile
import codeloupe.config.JobsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hands [apply] the jobs part of [file] whenever the file changes, looked at every [EVERY_MS] (one stat; the file is read only when its
 * time or size moved). A file that is not valid JSON — half written, or a hand edit with a typo — changes nothing until it is fixed.
 */
class SlotConfigWatch(private val file: Path, private val apply: (JobsConfig) -> Unit) {
    // The daemon started with the file as it is now: only a later change is news.
    private var seen: String? = stamp()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(EVERY_MS)
                runCatching { check() }
            }
        }
    }

    /** Applies the file if it changed since it was last applied, or anyway when [force]d (a write within the same millisecond and size). */
    @Synchronized
    fun check(force: Boolean = false) {
        val stamp = stamp()
        if (stamp == seen && !force) return
        val config = runCatching { JobsConfig.parse(ConfigFile.read(file.parent)) }.getOrNull() ?: return
        seen = stamp
        apply(config)
    }

    private fun stamp(): String? = runCatching { "${Files.getLastModifiedTime(file).toMillis()}/${Files.size(file)}" }.getOrNull()

    private companion object {
        const val EVERY_MS = 1_000L
    }
}
