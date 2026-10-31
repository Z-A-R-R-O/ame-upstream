package com.openclaw.assistant.backend

import com.openclaw.assistant.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.OffsetDateTime
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AME protocol v0.1 transport for the ZETRO conversation gateway.
 * This client exposes conversation events only; remote capability events are
 * never executed by this transport.
 */
class AmeZetroClient(
    override val config: AgentBackendConfig,
    private val http: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AgentClient {

    private sealed interface Signal {
        data object Open : Signal
        data class Message(val text: String) : Signal
        data class Failure(val cause: Throwable) : Signal
        data class Closed(val code: Int, val reason: String) : Signal
    }

    private data class Session(
        val socket: WebSocket,
        val incoming: Channel<Signal>,
        val sessionId: String,
        val conversationId: String,
        var nextOutboundSequence: Long = 1L,
        var lastInboundSequence: Long = -1L,
    )

    override suspend fun testConnection(): ConnectionTestResult {
        val started = System.currentTimeMillis()
        return try {
            val session = openSession()
            session.socket.send(event(
                sessionId = session.sessionId,
                sequence = session.nextOutboundSequence++,
                type = "session.close",
                payload = buildJsonObject { put("reason", "connection_test_complete") },
            ).toString())
            session.socket.close(1000, "Connection test complete")
            session.incoming.close()
            ConnectionTestResult(true, "AME protocol v0.1 connected", System.currentTimeMillis() - started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started)
        }
    }

    override fun sendMessage(
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ): Flow<AgentEvent> = flow {
        val userText = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }
            ?.content?.takeIf { it.isNotBlank() }
        if (userText == null) {
            emit(AgentEvent.Error("AME requires a non-empty user message"))
            return@flow
        }
        if (userText.length > 8_000) {
            emit(AgentEvent.Error("AME user messages are limited to 8,000 characters"))
            return@flow
        }

        emit(AgentEvent.Started())
        var session: Session? = null
        try {
            val conversationKey = options.sessionId?.takeIf { it.isNotBlank() }
            val activeSession = openSession(
                resumeConversationId = conversationKey?.let { AmeConversationStore.get(config.id, it) },
            )
            session = activeSession
            if (conversationKey != null) {
                AmeConversationStore.put(config.id, conversationKey, activeSession.conversationId)
            }
            val turnId = UUID.randomUUID().toString()
            val sent = activeSession.socket.send(event(
                sessionId = activeSession.sessionId,
                sequence = activeSession.nextOutboundSequence++,
                turnId = turnId,
                type = "user.turn",
                payload = buildJsonObject {
                    put("text", userText)
                    put("source", options.extra["source"]?.takeIf { it == "transcribed" } ?: "typed")
                    put("locale", options.extra["locale"]?.takeIf { it.isNotBlank() } ?: "en-IN")
                },
            ).toString())
            if (!sent) {
                emit(AgentEvent.Error("Could not send the user turn to ZETRO"))
                return@flow
            }

            withTimeout(RESPONSE_TIMEOUT_MS) {
                for (signal in activeSession.incoming) {
                    when (signal) {
                        is Signal.Message -> {
                            val incoming = parseAndValidate(signal.text, activeSession)
                            when (incoming.first) {
                                "assistant.delta" -> {
                                    val delta = incoming.second["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (delta.isNotEmpty()) emit(AgentEvent.TokenDelta(delta))
                                }
                                "assistant.complete" -> {
                                    val finalText = incoming.second["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    emit(AgentEvent.Completed(finalText))
                                    return@withTimeout
                                }
                                "session.error" -> {
                                    val message = incoming.second["message"]?.jsonPrimitive?.contentOrNull
                                        ?: "ZETRO session error"
                                    emit(AgentEvent.Error(message))
                                    return@withTimeout
                                }
                                else -> Unit
                            }
                        }
                        is Signal.Failure -> throw signal.cause
                        is Signal.Closed -> throw IllegalStateException(
                            "ZETRO closed the session (" + signal.code + "): " + signal.reason
                        )
                        Signal.Open -> Unit
                    }
                }
                throw IllegalStateException("ZETRO connection ended before the assistant completed")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.Error(e.message ?: e.javaClass.simpleName, e))
        } finally {
            session?.let {
                runCatching {
                    it.socket.send(event(
                        sessionId = it.sessionId,
                        sequence = it.nextOutboundSequence,
                        type = "session.close",
                        payload = buildJsonObject { put("reason", "turn_complete") },
                    ).toString())
                    it.socket.close(1000, "AME turn complete")
                }
                it.incoming.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun openSession(resumeConversationId: String? = null): Session {
        val endpoint = config.baseUrl?.trim().orEmpty()
        val token = config.apiKeyOrToken?.trim().orEmpty()
        require(endpoint.startsWith("wss://", ignoreCase = true)) {
            "AME requires a secure wss:// gateway endpoint"
        }
        require(token.isNotBlank()) { "AME gateway bearer token is missing" }

        val sessionId = UUID.randomUUID().toString()
        val incoming = Channel<Signal>(Channel.UNLIMITED)
        val request = try {
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + token)
                .header("X-AME-Protocol-Version", PROTOCOL_VERSION)
                .build()
        } catch (e: IllegalArgumentException) {
            incoming.close()
            throw IllegalArgumentException("Invalid AME gateway URL", e)
        }

        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                incoming.trySend(Signal.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_EVENT_CHARS) {
                    incoming.trySend(Signal.Failure(IllegalStateException("ZETRO event exceeds the 64 KB limit")))
                    webSocket.close(1009, "Event too large")
                } else {
                    incoming.trySend(Signal.Message(text))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.trySend(Signal.Failure(t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.trySend(Signal.Closed(code, reason))
            }
        })

        try {
            return withTimeout(CONNECT_TIMEOUT_MS) {
                var outboundSequence = 0L
                for (signal in incoming) {
                    when (signal) {
                        Signal.Open -> {
                            val open = event(
                                sessionId = sessionId,
                                sequence = outboundSequence++,
                                type = "session.open",
                                payload = buildJsonObject {
                                    put("deviceId", config.id)
                                    put("appVersion", APP_VERSION)
                                    put("deviceCapabilities", buildJsonArray { })
                                    resumeConversationId?.let { put("conversationId", it) }
                                },
                            )
                            if (!socket.send(open.toString())) {
                                throw IllegalStateException("Could not open AME session")
                            }
                        }
                        is Signal.Message -> {
                            val parsed = parseEnvelope(signal.text, sessionId, -1L)
                            val type = parsed["type"]?.jsonPrimitive?.contentOrNull
                            if (type == "session.accepted") {
                                val payload = parsed["payload"]?.jsonObject
                                    ?: throw IllegalStateException("ZETRO session.accepted has no payload")
                                val conversationId = payload["conversationId"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it.isNotBlank() }
                                    ?: throw IllegalStateException("ZETRO omitted conversationId")
                                return@withTimeout Session(
                                    socket = socket,
                                    incoming = incoming,
                                    sessionId = sessionId,
                                    conversationId = conversationId,
                                    nextOutboundSequence = outboundSequence,
                                    lastInboundSequence = parsed["sequence"]?.jsonPrimitive?.longOrNull ?: -1L,
                                )
                            }
                            if (type == "session.error") {
                                val payload = parsed["payload"]?.jsonObject
                                throw IllegalStateException(
                                    payload?.get("message")?.jsonPrimitive?.contentOrNull ?: "ZETRO rejected the session"
                                )
                            }
                        }
                        is Signal.Failure -> throw signal.cause
                        is Signal.Closed -> throw IllegalStateException(
                            "ZETRO closed during session setup (" + signal.code + "): " + signal.reason
                        )
                    }
                }
                throw IllegalStateException("ZETRO closed before accepting the AME session")
            }
        } catch (e: Exception) {
            socket.close(1000, "AME session setup failed")
            incoming.close()
            throw e
        }
    }

    private fun parseAndValidate(raw: String, session: Session): Pair<String, JsonObject> {
        val root = parseEnvelope(raw, session.sessionId, session.lastInboundSequence)
        val sequence = root["sequence"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalStateException("ZETRO event has no sequence")
        session.lastInboundSequence = sequence
        val type = root["type"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("ZETRO event has no type")
        require(type in SUPPORTED_SERVER_EVENTS) { "Unsupported AME server event: " + type }
        val payload = root["payload"]?.jsonObject
            ?: throw IllegalStateException("ZETRO event has no payload")
        when (type) {
            "assistant.delta" -> require(payload["text"]?.jsonPrimitive?.contentOrNull != null) {
                "ZETRO assistant.delta has no text"
            }
            "assistant.complete" -> require(payload["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
                "ZETRO assistant.complete has no response text"
            }
            "session.error" -> require(payload["message"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
                "ZETRO session.error has no message"
            }
        }
        return type to payload
    }

    private fun parseEnvelope(raw: String, expectedSessionId: String, previousSequence: Long): JsonObject {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IllegalStateException("ZETRO returned invalid JSON", it) }
        require(root["protocolVersion"]?.jsonPrimitive?.contentOrNull == PROTOCOL_VERSION) {
            "Unsupported AME protocol version"
        }
        require(root["eventId"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
            "ZETRO event has no eventId"
        }
        require(root["sessionId"]?.jsonPrimitive?.contentOrNull == expectedSessionId) {
            "ZETRO event belongs to another session"
        }
        val sequence = root["sequence"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalStateException("ZETRO event has invalid sequence")
        require(sequence >= 0 && sequence > previousSequence) {
            "ZETRO event sequence is duplicate or out of order"
        }
        val timestamp = root["timestamp"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("ZETRO event has no timestamp")
        require(runCatching { OffsetDateTime.parse(timestamp) }.isSuccess) {
            "ZETRO event has invalid timestamp"
        }
        require(root["payload"] is JsonObject) { "ZETRO event payload must be an object" }
        return root
    }

    private fun event(
        sessionId: String,
        sequence: Long,
        type: String,
        payload: JsonObject,
        turnId: String? = null,
    ): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("eventId", UUID.randomUUID().toString())
        put("sessionId", sessionId)
        if (turnId == null) put("turnId", kotlinx.serialization.json.JsonNull)
        else put("turnId", turnId)
        put("sequence", sequence)
        put("timestamp", Instant.now().toString())
        put("type", type)
        put("payload", payload)
    }

    companion object {
        const val PROTOCOL_VERSION = "0.1"
        private val APP_VERSION = BuildConfig.VERSION_NAME
        private const val MAX_EVENT_CHARS = 64 * 1024
        private val SUPPORTED_SERVER_EVENTS = setOf(
            "session.accepted",
            "session.close",
            "assistant.delta",
            "assistant.complete",
            "session.error",
            "capability.proposed",
            "approval.required",
            "approval.decided",
            "capability.execute",
            "capability.result",
        )
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RESPONSE_TIMEOUT_MS = 90_000L

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}


/** Keeps the server conversation associated with each local chat session in memory. */
internal object AmeConversationStore {
    private val conversations = ConcurrentHashMap<String, String>()

    fun get(backendId: String, localSessionId: String): String? =
        conversations["$backendId:$localSessionId"]

    fun put(backendId: String, localSessionId: String, conversationId: String) {
        conversations["$backendId:$localSessionId"] = conversationId
    }
}
