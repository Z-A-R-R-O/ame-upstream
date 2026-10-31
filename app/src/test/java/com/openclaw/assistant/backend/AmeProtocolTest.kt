package com.openclaw.assistant.backend

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmeProtocolTest {
    @Test
    fun acceptsValidSessionAcceptedEventForCurrentSession() {
        val sessionId = "session-1"
        val event = AmeProtocol.event(
            sessionId = sessionId,
            sequence = 0,
            type = "session.accepted",
            payload = buildJsonObject {
                put("conversationId", "conversation-1")
                put("serverTime", "2026-10-31T12:00:00Z")
            },
        )

        assertTrue(AmeProtocol.isValidEnvelope(event, sessionId, -1))
    }

    @Test
    fun rejectsEventsFromAnotherSession() {
        val event = AmeProtocol.event(
            sessionId = "session-1",
            sequence = 0,
            type = "assistant.delta",
            payload = buildJsonObject { put("text", "hello") },
        )

        assertFalse(AmeProtocol.isValidEnvelope(event, "session-2", -1))
    }

    @Test
    fun rejectsNonStringAssistantDelta() {
        val event = AmeProtocol.event(
            sessionId = "session-1",
            sequence = 0,
            type = "assistant.delta",
            payload = buildJsonObject { put("text", 123) },
        )

        assertFalse(AmeProtocol.isValidEnvelope(event, "session-1", -1))
    }

    @Test
    fun rejectsDuplicateOrOutOfOrderSequence() {
        val event = AmeProtocol.event(
            sessionId = "session-1",
            sequence = 4,
            type = "assistant.delta",
            payload = buildJsonObject { put("text", "hello") },
        )

        assertFalse(AmeProtocol.isValidEnvelope(event, "session-1", 4))
    }
}
