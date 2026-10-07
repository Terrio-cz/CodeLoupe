package codeloupe.platform

import java.nio.file.Path
import java.util.Base64

/**
 * Starts a process that outlives whoever asked for it. On Windows a plain child stays in its parent's job object,
 * and agent hosts (a headless `claude -p` run, its background tasks) kill that job at turn end — the daemon, and
 * every job it runs, with it. Win32_Process.Create starts the process from the WMI service instead: outside any job,
 * with a hidden console and the user's own environment — not the caller's, so whoever happens to start the daemon
 * cannot plant variables (PATH, a policy hook's settings) in it for every later job. Elsewhere the caller's environment
 * is cut down to [KEPT]; pass what a process needs as arguments.
 */
object DetachedStart {
    fun start(command: List<String>, workDir: Path) {
        if (NativeCalls.isWindows && runCatching { windows(command, workDir) }.getOrDefault(false)) return
        ProcessBuilder(command)
            .apply { if (!NativeCalls.isWindows) environment().keys.retainAll { it in KEPT || it.startsWith("LC_") } }
            .directory(workDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .outputStream.close()
    }

    private fun windows(command: List<String>, workDir: Path): Boolean {
        // -EncodedCommand: the script's quotes would not survive Windows command-line quoting.
        val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
        val shell = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .apply {
                environment()[CMDLINE] = command.joinToString(" ", transform = ::quote)
                environment()[CWD] = workDir.toString()
            }
            .start()
        shell.outputStream.close()
        val out = shell.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        if (shell.waitFor() != 0) return false
        val (code, pid) = out.lines().last().split(' ').map { it.toLongOrNull() ?: -1 } + listOf(-1L, -1L)
        return code == 0L && pid > 0
    }

    /** One argument as the Windows C runtime splits a command line. */
    internal fun quote(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) return arg
        val out = StringBuilder("\"")
        var slashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> slashes++
                '"' -> {
                    out.append("\\".repeat(slashes * 2 + 1)).append('"')
                    slashes = 0
                }
                else -> {
                    out.append("\\".repeat(slashes)).append(c)
                    slashes = 0
                }
            }
        }
        return out.append("\\".repeat(slashes * 2)).append('"').toString()
    }

    private val KEPT = setOf("HOME", "USER", "LOGNAME", "SHELL", "PATH", "LANG", "TMPDIR", "TZ")
    private const val CMDLINE = "CODELOUPE_SPAWN_CMDLINE"
    private const val CWD = "CODELOUPE_SPAWN_CWD"

    // CreateFlags 16 = CREATE_NEW_CONSOLE, shown hidden: the daemon's children get a console without a window.
    // Written with § for PowerShell's $, which a Kotlin string would interpolate.
    private val SCRIPT = """
        §ErrorActionPreference = 'Stop'
        §si = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0; CreateFlags = [uint32]16 }
        §r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = §env:CODELOUPE_SPAWN_CMDLINE; CurrentDirectory = §env:CODELOUPE_SPAWN_CWD; ProcessStartupInformation = §si }
        "§(§r.ReturnValue) §(§r.ProcessId)"
    """.trimIndent().replace('§', '$')
}
