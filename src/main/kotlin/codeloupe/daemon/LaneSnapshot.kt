package codeloupe.daemon

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LaneSnapshot(@EncodeDefault(EncodeDefault.Mode.ALWAYS) val running: String? = null, val waiting: List<String>)
