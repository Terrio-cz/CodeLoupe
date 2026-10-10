package codeloupe.know

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a fact is about; the target that goes with it is a repository name, an area, a path, a symbol or a task id. */
@Serializable
enum class FactScope(val word: String) {
    @SerialName("repo") REPO("repo"),
    @SerialName("area") AREA("area"),
    @SerialName("file") FILE("file"),
    @SerialName("symbol") SYMBOL("symbol"),
    @SerialName("task") TASK("task"),
}
