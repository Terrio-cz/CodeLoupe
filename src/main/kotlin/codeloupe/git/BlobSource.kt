package codeloupe.git

/** Reads blob contents by sha. */
fun interface BlobSource {
    /** Calls [onBlob] once with the UTF-8 text of each blob it has; returns how many it read. Missing blobs are skipped. */
    fun read(shas: Collection<String>, onBlob: (sha: String, text: String) -> Unit): Int
}
