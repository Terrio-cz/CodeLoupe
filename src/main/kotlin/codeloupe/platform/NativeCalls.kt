package codeloupe.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

/** Downcalls into the OS for the few facts the JDK does not expose. */
internal object NativeCalls {
    val isWindows: Boolean = System.getProperty("os.name").lowercase().startsWith("windows")

    fun kernel32(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        handle(SymbolLookup.libraryLookup("kernel32", Arena.global()), name, descriptor, *options)

    fun ntdll(name: String, descriptor: FunctionDescriptor): MethodHandle =
        handle(SymbolLookup.libraryLookup("ntdll", Arena.global()), name, descriptor)

    fun libc(name: String, descriptor: FunctionDescriptor): MethodHandle =
        handle(Linker.nativeLinker().defaultLookup(), name, descriptor)

    private fun handle(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        Linker.nativeLinker().downcallHandle(lookup.find(name).orElseThrow(), descriptor, *options)
}
