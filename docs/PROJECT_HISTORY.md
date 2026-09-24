# Assistant Project History / Continuation

## Purpose

Persistent continuation file for `aliiitavazoeiii-afk/assistant`. Before future changes, re-fetch the current branch/HEAD and read this file plus `docs/ARCHITECTURE.md` and `docs/OPS_CONTROL_PLANE.md`. GitHub state is authoritative; this file records design decisions, not live deployment state.

## v0.1 baseline

Android Kotlin/Compose Persian personal assistant with Node backend and OpenAI Responses API. Device tools: local notes, alarms/reminders, strict selfie alarm, contacts/calls/SMS, notifications, location/maps, app launching, SpeechRecognizer/TTS and experimental wake word. OpenAI key stays server-side.

## v0.2 control-plane work

Development branch: `assistant-v0.2-control-plane`, created from main commit `70f4de78badf110152cd35f6398cf6bc34d3ae94`.

Decisions and additions:

- Central backend remains the only OpenAI brain; never copy the OpenAI key to managed servers.
- Android now persists a private session ID and the backend maps it to `previous_response_id` for real multi-turn context.
- Durable server-side memory, tasks and goals are separate from Android-local notes.
- Android app connection token is encrypted with Android Keystore; legacy plaintext token migrates on read.
- Android calls/SMS require an explicit local confirmation dialog before execution.
- Retrieved SMS, notifications, logs, memories and integration output are treated as untrusted data, not instructions.
- Backend supports named integrations, deterministic automations, reports and audit logs.
- Linux monitoring nodes are read-only and HMAC-authenticated. There is deliberately no arbitrary shell or generic write endpoint.
- Service status/log names are allowlisted per node.
- VPN expiry uses an adapter contract. Generic `vpn_json.py` is included; panel-specific adapters require the real panel/API/schema.
- VPN expiry uses a configured local calendar timezone and includes expiries that happened earlier on the same day.
- Automated outbound renewal messages require explicit environment enablement, a message webhook, and are deduplicated by rule/user/expiry.
- External service write operations must be explicitly registered and remain gated by `ASSISTANT_ALLOW_INTEGRATION_WRITES`.
- Automation changes from the model remain gated by `ASSISTANT_ALLOW_AUTOMATION_CHANGES`.
- v0.2 default routine model is `gpt-5.6-luna` with `low` reasoning effort, configurable by environment.
- Backend can be fully installed with an empty `OPENAI_API_KEY`; AI turns return 503 until the key is added.

## Intentional boundaries / future adapters

- No general cross-app Accessibility controller yet.
- No banking/payment/authentication automation.
- No arbitrary remote Linux command execution.
- No panel-specific VPN adapter until the exact VPN panel/data source is known.
- No hard-coded Darma/expense API endpoints until those services expose stable assistant endpoints.
- Voice remains Android SpeechRecognizer + local TTS; Realtime voice is a later layer.
