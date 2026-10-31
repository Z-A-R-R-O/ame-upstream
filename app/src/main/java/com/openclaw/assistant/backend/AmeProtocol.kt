package com.openclaw.assistant.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.content
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.util.UUID

/**
 * AME <-> ZETRO protocol envelope and the conversation events consumed by Android.
 *
 * Device capability events are deliberately not executable here. They must pass
 * through a separate local permission/grant/approval dispatcher.
 */
internal object AmeProtocol {
    const val VERSION = "0.1"
    private val json = Json { ignoreUnknownKeys = true }

    fun event(
        sessionId: String,
        sequence: Long,
        type: String,
        payload: JsonObject,
        turnId: String? = null,
    ): JsonObject = buildJsonObject {
        put("protocolVersion", VERSION)
        put("eventId", UUID.randomUUID().toString())
        put("sessionId", sessionId)
        if (turnId == null) put("turnId", kotlinx.serialization.json.JsonNull)
        else put("turnId", turnId)
        put("sequence", sequence)
        put("timestamp", OffsetDateTime.now().toString())
        put("type", type)
        put("payload", payload)
    }

    fun parse(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrNull()

    fun isValidEnvelope(event: JsonObject, expectedSessionId: String, previousSequence: Long): Boolean {
        val version = event["protocolVersion"]?.stringValue()
        val eventId = event["eventId"]?.stringValue()
        val sessionId = event["sessionId"]?.stringValue()
        val sequence = event["sequence"]?.longValue()
        val timestamp = event["timestamp"]?.stringValue()
        val type = event["type"]?.stringValue()
        val payload = event["payload"] as? JsonObject
        val turnId = event["turnId"]

        if (version != VERSION || eventId.isNullOrBlank() || eventId.length > 160) return false
        if (sessionId != expectedSessionId || sequence == null || sequence <= previousSequence) return false
        if (turnId != null && turnId !is kotlinx.serialization.json.JsonNull &&
            (turnId.stringValue().isNullOrBlank() || (turnId.stringValue()?.length ?: 0) > 160)
        ) return false
        if (timestamp.isNullOrBlank() || runCatching { OffsetDateTime.parse(timestamp) }.isFailure) return false
        if (type == null || type !in SUPPORTED_EVENTS || payload == null) return false

        return when (type) {
            "session.accepted" ->
                payload["conversationId"]?.stringValue()?.isNotBlank() == true &&
                    payload["serverTime"]?.stringValue()?.let {
                        runCatching { OffsetDateTime.parse(it) }.isSuccess
                    } == true
            "assistant.delta" -> payload["text"]?.stringValue() != null
            "assistant.complete" -> payload["text"]?.stringValue()?.isNotBlank() == true
            "session.error" ->
                payload["code"]?.stringValue()?.isNotBlank() == true &&
                    payload["message"]?.stringValue()?.isNotBlank() == true &&
                    payload["retryable"]?.booleanValue() != null
            else -> true
        }
    }

    private fun kotlinx.serialization.json.JsonElement.stringValue(): String? =
        runCatching {
            jsonPrimitive.takeIf { it.isString }?.content
        }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.longValue(): Long? =
        runCatching {
            jsonPrimitive.takeIf { !it.isString }?.content?.toLongOrNull()
        }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.booleanValue(): Boolean? =
        runCatching {
            jsonPrimitive.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        }.getOrNull()

    private val SUPPORTED_EVENTS = setOf(
        "session.open", "session.accepted", "session.close",
        "transcript.partial", "user.turn", "assistant.delta", "assistant.complete",
        "capability.proposed", "approval.required", "approval.decided",
        "capability.execute", "capability.result", "session.error",
    )
}
