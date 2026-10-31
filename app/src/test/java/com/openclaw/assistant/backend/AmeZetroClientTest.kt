package com.openclaw.assistant.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmeZetroClientTest {

    @Test
    fun testConnectionRejectsInsecureWebSocketEndpoint() = runBlocking {
        val client = AmeZetroClient(
            AgentBackendConfig(
                displayName = "AME · ZETRO",
                type = BackendType.AME_ZETRO,
                baseUrl = "ws://localhost:8765/ame",
                apiKeyOrToken = "test-token",
            ),
        )

        val result = client.testConnection()

        assertTrue(!result.ok)
        assertTrue(result.message.contains("wss://"))
    }

    @Test
    fun testConnectionRejectsMissingBearerToken() = runBlocking {
        val client = AmeZetroClient(
            AgentBackendConfig(
                displayName = "AME · ZETRO",
                type = BackendType.AME_ZETRO,
                baseUrl = "wss://localhost:8765/ame",
                apiKeyOrToken = null,
            ),
        )

        val result = client.testConnection()

        assertTrue(!result.ok)
        assertTrue(result.message.contains("token"))
    }

    @Test
    fun rejectsEmptyUserMessageBeforeOpeningSocket() = runBlocking {
        val client = AmeZetroClient(
            AgentBackendConfig(
                displayName = "AME · ZETRO",
                type = BackendType.AME_ZETRO,
                baseUrl = "wss://localhost:8765/ame",
                apiKeyOrToken = "test-token",
            ),
        )

        val events = client.sendMessage(listOf(AgentMessage.user("   "))).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AgentEvent.Error)
    }

    @Test
    fun factoryCreatesAmeZetroClient() {
        val config = AgentBackendConfig(
            displayName = "AME · ZETRO",
            type = BackendType.AME_ZETRO,
            baseUrl = "wss://localhost:8765/ame",
            apiKeyOrToken = "test-token",
        )

        assertTrue(AgentClientFactory.create(config) is AmeZetroClient)
    }

    @Test
    fun conversationIdIsStableAcrossRestartsAndScopedToBackendAndChat() {
        val first = stableAmeConversationId("backend-1", "chat-1")
        val afterRestart = stableAmeConversationId("backend-1", "chat-1")

        assertEquals(first, afterRestart)
        assertEquals(first, java.util.UUID.fromString(first).toString())
        assertTrue(first != stableAmeConversationId("backend-1", "chat-2"))
        assertTrue(first != stableAmeConversationId("backend-2", "chat-1"))
    }

    @Test
    fun stableConversationIdRejectsBlankInputs() {
        assertTrue(runCatching { stableAmeConversationId("", "chat-1") }.isFailure)
        assertTrue(runCatching { stableAmeConversationId("backend-1", " ") }.isFailure)
    }
}
