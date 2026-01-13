package org.example.data.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnthropicRequestDto(
    val model: String,
    val messages: List<AnthropicMessageDto>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tools: List<AnthropicToolDto>? = null
)

@Serializable
data class AnthropicMessageDto(
    val role: String,
    val content: AnthropicMessageContent
)

/**
 * Контент сообщения может быть либо простой строкой, либо списком блоков контента
 */
@Serializable(with = AnthropicMessageContentSerializer::class)
sealed class AnthropicMessageContent {
    data class Text(val text: String) : AnthropicMessageContent()
    data class Blocks(val blocks: List<AnthropicContentBlockDto>) : AnthropicMessageContent()
}

/**
 * Определение инструмента для Anthropic API
 */
@Serializable
data class AnthropicToolDto(
    val name: String,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
data class AnthropicResponseDto(
    val id: String? = null,
    val type: String,
    val role: String? = null,
    val model: String? = null,
    val content: List<AnthropicContentBlockDto> = emptyList(),
    val usage: AnthropicUsageDto? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class AnthropicContentBlockDto(
    val type: String,
    val text: String? = null,
    // Для блоков tool_use
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    // Для блоков tool_result
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null
)

@Serializable
data class AnthropicUsageDto(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

@Serializable
data class StreamEventDto(
    val type: String,
    val message: StreamMessageDto? = null,
    val delta: StreamDeltaDto? = null,
    val usage: AnthropicUsageDto? = null,
    val index: Int? = null,
)

@Serializable
data class StreamMessageDto(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val model: String? = null,
    val usage: AnthropicUsageDto? = null,
)

@Serializable
data class StreamDeltaDto(
    val type: String? = null,
    val text: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

/**
 * Кастомный сериализатор для AnthropicMessageContent
 * Сериализует Text как простую строку, Blocks как JSON массив
 */
object AnthropicMessageContentSerializer : kotlinx.serialization.KSerializer<AnthropicMessageContent> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("AnthropicMessageContent")

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: AnthropicMessageContent) {
        val jsonEncoder = encoder as kotlinx.serialization.json.JsonEncoder
        when (value) {
            is AnthropicMessageContent.Text -> jsonEncoder.encodeJsonElement(
                kotlinx.serialization.json.JsonPrimitive(value.text)
            )
            is AnthropicMessageContent.Blocks -> jsonEncoder.encodeJsonElement(
                kotlinx.serialization.json.Json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(AnthropicContentBlockDto.serializer()),
                    value.blocks
                )
            )
        }
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): AnthropicMessageContent {
        val jsonDecoder = decoder as kotlinx.serialization.json.JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive ->
                AnthropicMessageContent.Text(element.content)
            is kotlinx.serialization.json.JsonArray ->
                AnthropicMessageContent.Blocks(
                    kotlinx.serialization.json.Json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(AnthropicContentBlockDto.serializer()),
                        element
                    )
                )
            else -> throw kotlinx.serialization.SerializationException("Unexpected content type")
        }
    }
}
