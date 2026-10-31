package com.openclaw.assistant.backend

object AgentClientFactory {
    fun create(config: AgentBackendConfig): AgentClient = when (config.type) {
        BackendType.HERMES_API_SERVER -> HermesApiServerClient(config)
        BackendType.AME_ZETRO -> AmeZetroClient(config)
        BackendType.OPENCLAW_GATEWAY -> OpenClawGatewayAdapter(config)
        BackendType.OPENCLAW_HTTP -> OpenClawHttpAdapter(config)
    }
}
