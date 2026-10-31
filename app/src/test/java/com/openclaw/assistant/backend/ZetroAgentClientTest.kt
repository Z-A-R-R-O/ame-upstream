package com.openclaw.assistant.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZetroAgentClientTest {
    @Test
    fun rejectsInsecureEndpointBeforeOpeningSocket() = runBlocking {
        val client = ZetroAgentClient(
            AgentBackendConfig(
                displayName = "ZETRO",
                type = BackendType.ZETRO_GATEWAY,
                baseUrl = "ws://127.0.0.1:8765/ame",
                apiKeyOrToken = "test-token",
            ),
        )

        val result = client.testConnection()

        assertFalse(result.ok)
        assertTrue(result.message.contains("wss://"))
    }

    @Test
    fun rejectsMissingBearerTokenBeforeOpeningSocket() = runBlocking {
        val client = ZetroAgentClient(
            AgentBackendConfig(
                displayName = "ZETRO",
                type = BackendType.ZETRO_GATEWAY,
                baseUrl = "wss://127.0.0.1:8765/ame",
                apiKeyOrToken = null,
            ),
        )

        val result = client.testConnection()

        assertFalse(result.ok)
        assertTrue(result.message.contains("token"))
    }

    @Test
    fun factoryCreatesZetroBackendClient() {
        val config = AgentBackendConfig(
            displayName = "ZETRO",
            type = BackendType.ZETRO_GATEWAY,
            baseUrl = "wss://127.0.0.1:8765/ame",
            apiKeyOrToken = "test-token",
        )

        assertTrue(AgentClientFactory.create(config) is ZetroAgentClient)
    }
}
