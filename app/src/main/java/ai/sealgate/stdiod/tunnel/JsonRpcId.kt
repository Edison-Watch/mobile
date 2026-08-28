package ai.sealgate.stdiod.tunnel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A JSON-RPC request id: an integer or a string on the wire
 * (`tunnel_error.related_jsonrpc_id` in the schema). JSON has one number
 * type, so the integer arm is a [Long].
 */
@Serializable(with = JsonRpcIdSerializer::class)
sealed interface JsonRpcId {
    data class Num(val value: Long) : JsonRpcId
    data class Str(val value: String) : JsonRpcId
}

/** Encodes [JsonRpcId] as a bare JSON number or string, never an object. */
object JsonRpcIdSerializer : KSerializer<JsonRpcId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ai.sealgate.stdiod.tunnel.JsonRpcId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): JsonRpcId {
        val input = decoder as? JsonDecoder
            ?: error("JsonRpcId only supports JSON")
        val primitive = input.decodeJsonElement().jsonPrimitive
        return when {
            primitive.isString -> JsonRpcId.Str(primitive.content)
            else -> JsonRpcId.Num(
                primitive.longOrNull ?: error("JSON-RPC id must be an integer or string"),
            )
        }
    }

    override fun serialize(encoder: Encoder, value: JsonRpcId) {
        val output = encoder as? JsonEncoder
            ?: error("JsonRpcId only supports JSON")
        when (value) {
            is JsonRpcId.Num -> output.encodeJsonElement(JsonPrimitive(value.value))
            is JsonRpcId.Str -> output.encodeJsonElement(JsonPrimitive(value.value))
        }
    }
}
