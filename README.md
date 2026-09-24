# Assistant

Ali's private Persian-first Android personal operations agent.

## Repository layout

- `app/` — Android app (Kotlin + Jetpack Compose)
- `server/` — Node.js 22 OpenAI/control-plane backend
- `node-agent/` — optional read-only Linux/VPN monitoring node
- `docs/` — architecture, setup, operations runbook and continuation history

## v0.2 capabilities

### Android

- Persian speech input and local spoken responses
- local notes/ideas
- exact alarms and spoken reminders
- strict fresh-selfie alarm challenge
- contacts, calls and SMS
- recent notification access
- location, Google Maps and installed-app launching
- experimental wake word
- explicit local confirmation before call/SMS execution
- Android Keystore-backed app connection token
- persistent session ID for multi-turn server context

### Control plane

- OpenAI Responses API with server-side tool loop
- durable private memory
- tasks and measurable goals
- named/allowlisted HTTP integrations for business systems
- read-only monitoring nodes for server health/service logs
- VPN-expiry adapter contract
- deterministic automations and reports
- optional renewal-message webhook with duplicate prevention
- append-only audit events

## Security model

- OpenAI API key stays only on the central backend.
- Monitoring nodes are read-only and expose no arbitrary shell endpoint.
- Android calls/SMS require local confirmation.
- Integration writes, automation changes and outbound messages are disabled by environment gates until explicitly enabled.
- Retrieved notifications/SMS/logs/integration responses are treated as untrusted data, not model instructions.
- Payment, banking, OTP/password entry and authentication remain manual.

## Install now, add OpenAI later

The central backend can be installed before your OpenAI API balance/key is ready:

```bash
sudo bash server/install.sh
curl http://127.0.0.1:8787/health
```

The installer generates the separate `ASSISTANT_APP_TOKEN`. `OPENAI_API_KEY` can remain empty; AI requests return a clear 503 until it is added.

When the API key is ready:

```bash
sudo nano server/.env
# OPENAI_API_KEY=sk-...
sudo systemctl restart assistant
```

Optional read-only monitoring node:

```bash
sudo bash node-agent/install.sh
```

Read `docs/OPS_CONTROL_PLANE.md` before exposing any node remotely.

## Model defaults

v0.2 defaults to `gpt-5.6-luna` with `low` reasoning effort for low-cost routine operation. Both are configurable in `server/.env`.
