package com.openclaw.assistant.backend

import kotlinx.serialization.Serializable

@Serializable
enum class BackendType {
    OPENCLAW_GATEWAY,
    OPENCLAW_HTTP,
    HERMES_API_SERVER,
    AME_ZETRO,
}
