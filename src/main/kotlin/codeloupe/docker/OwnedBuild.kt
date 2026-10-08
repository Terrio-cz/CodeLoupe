package codeloupe.docker

import java.nio.file.Files
import java.nio.file.Path

/** `docker build` with the ownership labels on the image, checked afterwards through the API. */
class OwnedBuild(private val cli: DockerCli, private val api: () -> DockerApi, private val note: (String) -> Unit) {
    /** [args] are the arguments of `docker build` (options and the context). Answers its exit code, or [UNLABELED] when the image came out without the labels. */
    fun build(dir: Path, ownership: Ownership, args: List<String>): Int {
        OwnLabels.rejectOwn(args)
        val idFile = Files.createTempFile("codeloupe-build-", ".iid")
        try {
            val exit = cli.run(listOf("build") + OwnLabels.flags(ownership) + listOf("--iidfile", idFile.toString()) + args, dir)
            if (exit != 0) return exit
            val id = Files.readString(idFile).trim()
            if (id.isEmpty()) {
                note("the build wrote no image id (output to a file or a registry?); the labels could not be checked")
                return 0
            }
            if (Ownership.of(api().imageLabels(id).orEmpty()) != ownership) {
                note("image $id does not carry the CodeLoupe labels")
                return UNLABELED
            }
            return 0
        } finally {
            Files.deleteIfExists(idFile)
        }
    }

    companion object {
        const val UNLABELED = 3
    }
}
