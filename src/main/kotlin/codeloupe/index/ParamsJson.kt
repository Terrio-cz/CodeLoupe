package codeloupe.index

import codeloupe.lang.ParamFact
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The `params` column of a declaration: `[{"name","type","default"?,"vararg"?}]`, flags only when set. */
object ParamsJson {
    fun of(params: List<ParamFact>): String = JsonArray(
        params.map { p ->
            buildJsonObject {
                put("name", JsonPrimitive(p.name))
                put("type", JsonPrimitive(p.type))
                if (p.default) put("default", JsonPrimitive(true))
                if (p.vararg) put("vararg", JsonPrimitive(true))
            }
        },
    ).toString()
}
