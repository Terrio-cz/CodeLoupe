package codeloupe.ports

/** What is listening on this machine; a fake in tests, [LocalPorts] for real. */
interface PortProbe {
    /** Whether something holds [port] right now: it cannot be bound, or a connection to it succeeds. */
    fun inUse(port: Int): Boolean

    /** Every listening TCP port with its process, where the OS says which. One call to an OS tool, so use it for reports, not in a loop. */
    fun listeners(): Map<Int, Listener>
}
