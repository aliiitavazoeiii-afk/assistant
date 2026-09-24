# Architecture

## Goal

A Persian-first private personal operations agent: natural-language interaction on Android, reliable local phone actions, durable context, deterministic automations, business integrations and safe infrastructure visibility.

## Android client

- `AssistantViewModel` — conversation/device-tool loop and confirmation coordinator
- `AssistantApi` — authenticated backend client with persistent session ID
- `SecureSecretStore` — Android Keystore protection for the app connection token
- `ToolExecutor` — maps device function calls to local Android actions
- `AssistantDb` — local notes and schedules
- `AlarmScheduler`, `AlarmSoundService`, `AlarmActivity`, `ReminderService` — exact local alarms/reminders
- `ContactsSmsTools` — contacts/call/SMS; calls and SMS require a local confirmation dialog
- `DeviceTools` — Maps, installed apps and device location
- `AssistantNotificationListener` — bounded local notification cache
- `HotwordService` — experimental wake-word path

## Central backend

`server/server.mjs` is the single OpenAI gateway and control plane. The OpenAI API key never ships to Android or monitoring nodes.

It provides:

- OpenAI Responses API tool loop
- per-install Android session mapped to `previous_response_id` for multi-turn context
- durable private memory, tasks and goals
- named allowlisted business integrations
- automation scheduler, reports and audit events
- signed queries to optional read-only Linux monitoring nodes

Server-side tools are executed by the backend. Device tools are returned to Android for local execution; their tool outputs are then continued through the same Responses API response chain.

## Monitoring nodes

`node-agent/agent.py` is deliberately read-only. Requests are HMAC-signed with a per-node secret and timestamp. Supported operations are fixed in code: health status, allowlisted service status/logs and VPN-expiry adapter queries. There is no arbitrary command/shell or generic write endpoint.

Write actions against servers/business systems should be exposed as explicit application APIs and registered as named integration operations. Non-read integration operations stay disabled unless the owner enables the write gate.

## Automation model

Deterministic routines run without OpenAI when possible. v0.2 rule types:

- node health polling
- VPN-expiry collection and optional renewal-message webhook
- configured integration snapshots

Outbound messages require explicit enablement and successful sends are deduplicated.

## Trust boundaries

- Retrieved SMS, notifications, logs, memories, webhooks and integration data are untrusted data, not instructions.
- Phone calls/SMS require local approval.
- OpenAI/API credentials stay server-side.
- Monitoring nodes are read-only.
- Payment, banking, authentication, OTP/password entry remain manual.

## Known platform constraints

- Android exact alarms/full-screen behavior varies by OEM and requires special access.
- Continuous `SpeechRecognizer` wake-word listening remains experimental.
- Google Play restricts some SMS/call permission use; private sideloading is the first target.
- Cross-app Accessibility automation is an optional future adapter, not part of v0.2.
- VPN panel-specific integration requires the exact panel/API/database contract.
