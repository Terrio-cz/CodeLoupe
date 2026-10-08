package codeloupe.processes

/** The running processes of this machine, as many as the OS lets this user see. */
fun interface ProcessSource {
    fun read(): List<ProcessInfo>
}
