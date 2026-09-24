#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then echo "Run as root: sudo bash server/install.sh"; exit 1; fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="$ROOT/server"
command -v node >/dev/null || { echo "Node.js 22+ is required."; exit 1; }
NODE_MAJOR="$(node -p 'process.versions.node.split(`.`)[0]')"
[[ "$NODE_MAJOR" -ge 22 ]] || { echo "Node.js 22+ is required; found $(node -v)"; exit 1; }

if [[ ! -f "$SERVER/.env" ]]; then
  cp "$SERVER/.env.example" "$SERVER/.env"
  TOKEN="$(openssl rand -hex 32)"
  sed -i "s/^ASSISTANT_APP_TOKEN=.*/ASSISTANT_APP_TOKEN=$TOKEN/" "$SERVER/.env"
  echo "Generated ASSISTANT_APP_TOKEN: $TOKEN"
else
  echo "Keeping existing server/.env"
fi
[[ -f "$SERVER/config/nodes.json" ]] || cp "$SERVER/config/nodes.example.json" "$SERVER/config/nodes.json"
[[ -f "$SERVER/config/integrations.json" ]] || cp "$SERVER/config/integrations.example.json" "$SERVER/config/integrations.json"
mkdir -p "$SERVER/data"
[[ -f "$SERVER/data/automations.json" ]] || cp "$SERVER/config/automations.example.json" "$SERVER/data/automations.json"

id assistant >/dev/null 2>&1 || useradd --system --home "$ROOT" --shell /usr/sbin/nologin assistant
chown -R assistant:assistant "$SERVER/data"
chmod 600 "$SERVER/.env"

cat >/etc/systemd/system/assistant.service <<UNIT
[Unit]
Description=Ali Personal Assistant Control Plane
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=assistant
Group=assistant
WorkingDirectory=$SERVER
ExecStart=$(command -v node) $SERVER/server.mjs
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ReadWritePaths=$SERVER/data

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now assistant
sleep 1
curl -fsS http://127.0.0.1:8787/health || { systemctl status assistant --no-pager; exit 1; }
echo
echo "Assistant backend installed. OPENAI_API_KEY may remain empty until billing is active."
echo "Edit: $SERVER/.env"
echo "Then: systemctl restart assistant"
