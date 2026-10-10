package codeloupe.know

import kotlinx.serialization.Serializable

/** The facts whose anchors say they may be out of date; [checked] anchored facts were read, [unreachable] of them in a checkout that could not be read. */
@Serializable
data class StaleReport(val checked: Int, val unreachable: Int, val stale: List<KnowHit>)
