package codeloupe.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.util.concurrent.TimeUnit

/**
 * JGit's view of the machine for a daemon that only reads repositories: no system config (JGit runs git to find
 * it), no user config, and no timestamp-resolution probes that JGit would write into a repository and save to
 * `~/.config/jgit/config`.
 */
internal class ReadOnlySystemReader(delegate: SystemReader) : SystemReader.Delegate(delegate) {
    override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)

    override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)

    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)

    private fun empty(parent: Config?, fs: FS): FileBasedConfig = object : FileBasedConfig(parent, null, fs) {
        override fun load() = Unit

        override fun save() = Unit

        override fun isOutdated() = false

        // JGit asks this before measuring a file store. Its own coarse fallback only makes snapshots of recently
        // modified files count as changed, so they are read again: slower at worst, never stale.
        override fun getTimeUnit(section: String?, subsection: String?, name: String?, defaultValue: Long, wantUnit: TimeUnit): Long =
            if (section == "filesystem" && name == "timestampResolution") {
                wantUnit.convert(FS.FileStoreAttributes.FALLBACK_TIMESTAMP_RESOLUTION)
            } else {
                super.getTimeUnit(section, subsection, name, defaultValue, wantUnit)
            }
    }
}
