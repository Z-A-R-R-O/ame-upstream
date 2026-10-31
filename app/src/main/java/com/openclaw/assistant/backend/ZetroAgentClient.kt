package com.openclaw.assistant.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.content
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * AME's native client for the versioned ZETRO conversation protocol.
 *
 * This is conversation transport only. It intentionally ignores capability
 * execution events; Android actions require a separate audited approval path.
 */
class ZetroAgentClient(
    override val config: AgentBackendConfig,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : AgentClient {
    @Volatile private var activeSocket: WebSocket? = null
    private val deviceId: String = UUID.randomUUID().toString()

    private val endpoint: String
        get() = config.baseUrl?.trim().orEmpty()

    private val token: String
        get() = config.apiKeyOrToken?.trim().orEmpty()

    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (!endpoint.startsWith("wss://", ignoreCase = true)) {
            return@withContext ConnectionTestResult(false, "ZETRO requires a secure wss:// endpoint")
        }
        if (token.isBlank()) {
            return@withContext ConnectionTestResult(false, "ZETRO bearer token is missing")
        }

        val startedAt = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        val accepted = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val request = runCatching { requestBuilder().url(endpoint).build() }.getOrNull()
                if (request == null) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                var socket: WebSocket? = null
                socket = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val sent = webSocket.send(AmeProtocol.event(
                            sessionId = sessionId,
                            sequence = 0,
                            type = "session.open",
                            payload = sessionOpenPayload(),
                        ).toString())
                        if (!sent && continuation.isActive) {
                            continuation.resume(false)
                            webSocket.close(1011, "Could not send session.open")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val event = AmeProtocol.parse(text) ?: return
                        if (!AmeProtocol.isValidEnvelope(event, sessionId, -1)) return
                        when (event["type"]?.jsonPrimitive?.content) {
                            "session.accepted" -> if (continuation.isActive) continuation.resume(true)
                            "session.error" -> if (continuation.isActive) continuation.resume(false)
                        }
                        webSocket.close(1000, "Handshake check complete")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
                continuation.invokeOnCancellation { socket?.cancel() }
            }
        } ?: false

        ConnectionTestResult(
            ok = accepted,
            message = if (accepted) "ZETRO session accepted" else "No valid ZETRO session response",
            latencyMs = System.currentTimeMillis() - startedAt,
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun sendMessage(
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ): Flow<AgentEvent> = callbackFlow {
        val output = this
        val userText = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }
            ?.content?.trim().orEmpty()
        if (userText.isBlank()) {
            output.trySend(AgentEvent.Error("ZETRO message is empty"))
            output.close()
            return@callbackFlow
        }
        if (userText.length > 8_000) {
            output.trySend(AgentEvent.Error("ZETRO messages are limited to 8,000 characters"))
            output.close()
            return@callbackFlow
        }
        if (!endpoint.startsWith("wss://", ignoreCase = true) || token.isBlank()) {
            output.trySend(AgentEvent.Error("Configure a secure ZETRO wss:// endpoint and bearer token"))
            output.close()
            return@callbackFlow
        }

        val sessionId = UUID.randomUUID().toString()
        val turnId = UUID.randomUUID().toString()
        val completed = AtomicBoolean(false)
        var inboundSequence = -1L
        var outboundSequence = 0L
        var socket: WebSocket? = null

        val request = runCatching { requestBuilder().url(endpoint).build() }.getOrElse {
            output.trySend(AgentEvent.Error("Invalid ZETRO gateway URL", it))
            output.close()
            return@callbackFlow
        }

        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeSocket = webSocket
                output.trySend(AgentEvent.Started())
                val open = AmeProtocol.event(
                    sessionId = sessionId,
                    sequence = outboundSequence++,
                    type = "session.open",
                    payload = sessionOpenPayload(options.sessionId),
                )
                if (!webSocket.send(open.toString())) {
                    if (completed.compareAndSet(false, true)) {
                        output.trySend(AgentEvent.Error("Could not open a ZETRO session"))
                        output.close()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_EVENT_CHARS) {
                    if (completed.compareAndSet(false, true)) {
                        output.trySend(AgentEvent.Error("ZETRO event exceeded the 64 KB limit"))
                        output.close()
                        webSocket.close(1009, "Event too large")
                    }
                    return
                }
                val event = AmeProtocol.parse(text)
                if (event == null || !AmeProtocol.isValidEnvelope(event, sessionId, inboundSequence)) {
                    if (completed.compareAndSet(false, true)) {
                        output.trySend(AgentEvent.Error("ZETRO returned an invalid protocol event"))
                        output.close()
                        webSocket.close(1002, "Invalid AME event")
                    }
                    return
                }

                inboundSequence = event["sequence"]!!.jsonPrimitive.long
                val type = event["type"]?.toString()?.trim('"')
                val payload = event["payload"] as? JsonObject ?: return
                when (type) {
                    "session.accepted" -> {
                        val turn = AmeProtocol.event(
                            sessionId = sessionId,
                            sequence = outboundSequence++,
                            type = "user.turn",
                            turnId = turnId,
                            payload = buildJsonObject {
                                put("text", userText)
                                put("source", options.extra["ameSource"]?.takeIf {
                                    it == "transcribed" || it == "typed"
                                } ?: "typed")
                                put("locale", options.extra["locale"] ?: "en-IN")
                            },
                        )
                        if (!webSocket.send(turn.toString())) {
                            if (completed.compareAndSet(false, true)) {
                                output.trySend(AgentEvent.Error("Could not send message to ZETRO"))
                                output.close()
                            }
                        }
                    }
                    "assistant.delta" -> {
                        val delta = payload["text"]?.jsonPrimitive?.content ?: ""
                        if (delta.isNotEmpty()) output.trySend(AgentEvent.TokenDelta(delta))
                    }
                    "assistant.complete" -> {
                        val finalText = payload["text"]?.jsonPrimitive?.content.orEmpty()
                        if (completed.compareAndSet(false, true)) {
                            output.trySend(AgentEvent.Completed(finalText))
                            output.close()
                            webSocket.close(1000, "Turn complete")
                        }
                    }
                    "session.error" -> {
                        val message = payload["message"]?.jsonPrimitive?.content
                            ?: "ZETRO session error"
                        if (completed.compareAndSet(false, true)) {
                            output.trySend(AgentEvent.Error(message))
                            output.close()
                            webSocket.close(1011, "ZETRO session error")
                        }
                    }
                    // Capability events are deliberately not dispatched here.
                    else -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeSocket = null
                if (completed.compareAndSet(false, true)) {
                    output.trySend(AgentEvent.Error(t.message ?: "ZETRO connection failed", t))
                    output.close()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeSocket = null
                if (completed.compareAndSet(false, true)) {
                    output.trySend(AgentEvent.Error("ZETRO closed the session before completing the turn"))
                    output.close()
                }
            }
        })

        awaitClose {
            activeSocket = null
            socket?.close(1000, "AME request finished")
        }
    }

    override suspend fun stopCurrentRun(): Boolean {
        val socket = activeSocket ?: return false
        activeSocket = null
        return socket.close(1000, "User interrupted AME response")
    }

    private fun requestBuilder(): Request.Builder =
        Request.Builder()
            .header("Authorization", "Bearer $token")
            .header("X-AME-Protocol-Version", AmeProtocol.VERSION)

    private fun sessionOpenPayload(conversationId: String? = null): JsonObject = buildJsonObject {
        put("deviceId", deviceId)
        put("appVersion", com.openclaw.assistant.BuildConfig.VERSION_NAME)
        put("deviceCapabilities", kotlinx.serialization.json.buildJsonArray { })
        conversationId?.takeIf { it.isNotBlank() }?.let { put("conversationId", it) }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 12_000L
        private const val MAX_EVENT_CHARS = 64 * 1024
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}
