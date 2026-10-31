# AME / ZETRO Android integration

This branch adds ZETRO as a selectable backend in WakeHermesClaw's existing backend architecture.

## Configure

In **Settings → Backends → Add**, select **ZETRO** and provide:

- A secure WebSocket endpoint beginning with `wss://`
- A ZETRO-issued bearer token

The backend repository already uses encrypted preferences for credentials. The ZETRO client sends the token in the WebSocket `Authorization: Bearer` handshake header and never includes it in event payloads or logs.

## Conversation protocol

The client implements AME protocol v0.1:

1. Open an authenticated WSS connection with `X-AME-Protocol-Version: 0.1`.
2. Send `session.open` with device ID, app version and an empty device-capability list.
3. Wait for a valid `session.accepted` event for the same session.
4. Send the user turn as `user.turn`.
5. Convert `assistant.delta` events to `AgentEvent.TokenDelta`.
6. Convert `assistant.complete` to `AgentEvent.Completed`.

Inbound events are checked for protocol version, session identity, event ID, timestamp, type, and monotonically increasing sequence. Events larger than 64 KB are rejected.

## Safety boundary

This first integration is conversation-only. Events such as `capability.proposed`, `approval.required`, `approval.decided`, and `capability.execute` are not dispatched to Android APIs.

Before any device action is enabled, AME needs a separate capability dispatcher that validates:
- The Android runtime permission and the exact capability scope
- Grant identity, expiry and revocation
- Approval ID and request digest for approval-required actions
- User-visible confirmation for sensitive actions
- A truthful result separating action state, effect and verification

Do not treat a ZETRO response or a protocol event as permission to execute an Android action.

## Server requirement

The ZETRO-side WebSocket gateway adapter must implement the matching AME v0.1 contract and authenticate bearer tokens. This Android branch does not deploy that server. Until a compatible endpoint exists, the app can configure the backend but cannot complete a live conversation.

## Build validation

Use the repository's standard CI checks:

```bash
cp app/google-services.json.example app/google-services.json
FIREBASE_ENABLED=false ./gradlew testStandardDebugUnitTest
FIREBASE_ENABLED=false ./gradlew assembleStandardDebug
```
