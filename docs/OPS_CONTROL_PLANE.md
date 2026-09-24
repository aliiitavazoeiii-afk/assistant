# Assistant v0.2 — Operations Control Plane

## Design

One central Assistant backend is the brain. OpenAI credentials live only there. Other Linux/VPN servers may run a **read-only** `assistant-node` monitor with a unique shared secret. The central server signs node queries with HMAC-SHA256 and a short timestamp window.

The monitoring node intentionally has no generic shell endpoint and no write/restart operation. Read operations are fixed in code and service names are allowlisted in `node-agent/config.json`. Server-side write actions must be exposed as explicit named operations by a trusted application/API and registered in `server/config/integrations.json`.

## Central installation

From the repository root:

```bash
sudo bash server/install.sh
sudo systemctl status assistant --no-pager
curl http://127.0.0.1:8787/health
```

The installer creates `server/.env`, a random `ASSISTANT_APP_TOKEN`, runtime data, systemd service, and private runtime configs. Keep the generated app token private and enter the same value in Android.

`OPENAI_API_KEY` may remain empty. The server and automation engine still start; AI turns return a clear 503 until the key is configured.

When API billing/key is ready:

```bash
sudo nano server/.env
# set OPENAI_API_KEY=sk-...
sudo systemctl restart assistant
curl http://127.0.0.1:8787/health
```

The backend binds to `127.0.0.1` by default. Put it behind HTTPS/Caddy or a private VPN before using a physical phone remotely.

## Monitoring-node installation

On each server you want to monitor:

```bash
sudo bash node-agent/install.sh
sudo systemctl status assistant-node --no-pager
curl http://127.0.0.1:9443/health
```

The installer creates a dedicated unprivileged `assistant-node` user and a unique `AGENT_SECRET`. Add that secret to a unique environment variable on the central controller and register the node in `server/config/nodes.json`.

Example:

```json
{
  "nodes": [
    {
      "id": "vpn-1",
      "name": "VPN Server 1",
      "url": "http://10.10.0.11:9443",
      "secretEnv": "ASSISTANT_NODE_VPN1_SECRET"
    }
  ]
}
```

Then in central `server/.env`:

```env
ASSISTANT_NODE_VPN1_SECRET=<secret printed by node installer>
```

Use the HTTP example only over a private network such as WireGuard. For internet-facing transport configure `AGENT_TLS_CERT` / `AGENT_TLS_KEY` and firewall the node port to the controller address.

## VPN expiry adapter

`vpn_expiring` invokes only the configured adapter command. The bundled `node-agent/adapters/vpn_json.py` is a generic adapter for testing or panels that can export users to JSON.

Source contract:

```json
{
  "users": [
    {
      "id": "123",
      "username": "customer1",
      "name": "Customer",
      "recipient": "0912...",
      "expiresAt": "2026-09-24T18:00:00+03:30",
      "enabled": true
    }
  ]
}
```

For `daysAhead=0`, the adapter returns all users whose expiry falls anywhere in **today's local calendar day**, including users whose expiry time already passed earlier today. `VPN_TIMEZONE` controls that calendar (default `Asia/Tehran`).

A panel-specific adapter (3x-ui, Marzban, custom DB/API, etc.) should produce the same output contract. Panel credentials remain outside model context.

## Renewal messaging

A `vpn_expiry` automation can send renewal messages via a configured message webhook. Sending is disabled unless:

- rule `sendMessages` is true
- `ASSISTANT_ALLOW_MESSAGES=true`
- `ASSISTANT_MESSAGE_WEBHOOK` is configured

The webhook receives `{ recipient, message, user }`. That lets you plug SMS, Telegram, WhatsApp Business, CRM, or your own sender into the same workflow. Successful messages are deduplicated by rule + user + expiry timestamp, so re-running a rule does not normally resend the same renewal notice.

## Business integrations

`server/config/integrations.json` registers named operations. The model can only call operations explicitly listed there. This is the intended path for Darma, expense, CRM, VPN panel write APIs, GitHub/deployment gateways, or any other internal service.

Read operations are available when configured. Operations marked with a non-read risk are blocked unless `ASSISTANT_ALLOW_INTEGRATION_WRITES=true`.

## Automations

Supported deterministic rule types in v0.2:

- `node_health`
- `vpn_expiry`
- `integration_snapshot`

Rules live in private runtime state at `server/data/automations.json`. The installer seeds an hourly health rule and a disabled VPN-expiry example. Automation changes from the model are disabled unless `ASSISTANT_ALLOW_AUTOMATION_CHANGES=true`.

## Security rules

1. No OpenAI key on monitoring nodes.
2. No arbitrary shell endpoint on monitoring nodes.
3. Use unique node secrets.
4. Prefer private networking; otherwise TLS + firewall allowlisting.
5. Keep integration writes and outbound messaging disabled until configured and tested read-only.
6. Payment, banking, authentication, OTP/password entry remain manual.
