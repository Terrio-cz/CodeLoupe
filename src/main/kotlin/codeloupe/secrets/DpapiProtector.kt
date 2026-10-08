package codeloupe.secrets

import java.util.Base64

/** Windows DPAPI (current user): only this user on this machine can unwrap. Driven through PowerShell with the key on stdin. */
class DpapiProtector : KeyProtector {
    override val name = "dpapi"

    override fun wrap(key: ByteArray): String = call("Protect", Base64.getEncoder().encodeToString(key))

    override fun unwrap(blob: String): ByteArray = Base64.getDecoder().decode(call("Unprotect", blob))

    private fun call(method: String, input: String): String {
        val script = "Add-Type -AssemblyName System.Security; " +
            "\$b = [Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); " +
            "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$method(\$b, \$null, 'CurrentUser'))"
        val result = Exec.run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), stdin = input)
        check(result.exit == 0 && result.out.isNotEmpty()) { "DPAPI $method failed (exit ${result.exit})" }
        return result.out
    }

    companion object {
        fun available(): Boolean = System.getProperty("os.name").lowercase().startsWith("windows") &&
            runCatching { DpapiProtector().let { it.unwrap(it.wrap(ByteArray(8) { b -> b.toByte() })).size == 8 } }.getOrDefault(false)
    }
}
