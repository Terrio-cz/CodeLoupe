package codeloupe.jobs

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * `<home>/jobs/<id>.exit`: the exit code of a job, written by a small PowerShell watcher that holds the job's process and its job
 * object open. It is what a daemon reads when the job ended while no daemon ran: once nobody holds a handle to an ended process,
 * Windows forgets its exit code.
 */
internal object ExitFile {
    fun read(file: Path): Int? = runCatching { Files.readString(file).trim().toInt() }.getOrNull()

    fun delete(file: Path) {
        runCatching { Files.deleteIfExists(file) }
    }

    /** The command line of the watcher of process [pid], which it inherits as [handle]; it writes [file] when the process ends. */
    fun watcher(pid: Long, handle: Long, file: Path): String {
        val script = SCRIPT.replace("{pid}", pid.toString()).replace("{handle}", handle.toString()).replace("{file}", file.toString().replace("'", "''"))
        // -EncodedCommand: no quoting of the script on the command line, and no execution policy for a script file.
        return "\"$POWERSHELL\" -NoLogo -NoProfile -NonInteractive -EncodedCommand " + Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    }

    private val POWERSHELL = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString()

    // The watcher inherits a handle to the process and reads the exit code through it: the process may end before PowerShell has
    // started, and only a handle keeps an ended process's code. .NET Framework takes a handle into a Process object only through
    // its private SetProcessHandle; should that ever fail, the pid is the fallback (it cannot be reused while the handle is held).
    // Written with § for PowerShell's $, which a Kotlin string would interpolate; the file is written beside and renamed, so a
    // reader never sees half of it.
    private val SCRIPT = """
        §ErrorActionPreference = 'Stop'
        try {
            §p = New-Object System.Diagnostics.Process
            §h = [Microsoft.Win32.SafeHandles.SafeProcessHandle]::new([IntPtr]{handle}, §true)
            [System.Diagnostics.Process].GetMethod('SetProcessHandle', [Reflection.BindingFlags]'NonPublic,Instance').Invoke(§p, @(§h)) | Out-Null
        } catch {
            §p = [System.Diagnostics.Process]::GetProcessById({pid})
        }
        §p.WaitForExit()
        §f = '{file}'
        [System.IO.File]::WriteAllText(§f + '.tmp', [string]§p.ExitCode)
        Move-Item -LiteralPath (§f + '.tmp') -Destination §f -Force
    """.trimIndent().replace('§', '$')
}
