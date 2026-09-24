#!/usr/bin/env bash
set -euo pipefail
if [[ ${EUID:-$(id -u)} -ne 0 ]]; then echo "Run as root: sudo bash node-agent/install.sh"; exit 1; fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT="$ROOT/node-agent"
command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }
id assistant-node >/dev/null 2>&1 || useradd --system --home /var/lib/assistant-node --create-home --shell /usr/sbin/nologin assistant-node
getent group systemd-journal >/dev/null && usermod -a -G systemd-journal assistant-node || true

if [[ ! -f "$AGENT/.env" ]]; then
  cp "$AGENT/.env.example" "$AGENT/.env"
  SECRET="$(openssl rand -hex 32)"
  sed -i "s/^AGENT_SECRET=.*/AGENT_SECRET=$SECRET/" "$AGENT/.env"
  echo "Generated AGENT_SECRET: $SECRET"
else
  echo "Keeping existing node-agent/.env"
fi
[[ -f "$AGENT/config.json" ]] || cp "$AGENT/config.example.json" "$AGENT/config.json"
mkdir -p /etc/assistant-node
if [[ ! -f /etc/assistant-node/vpn-users.json ]]; then printf '{"users":[]}\n' >/etc/assistant-node/vpn-users.json; fi
chown assistant-node:assistant-node "$AGENT/.env" /etc/assistant-node/vpn-users.json
chmod 600 "$AGENT/.env" /etc/assistant-node/vpn-users.json

cat >/etc/systemd/system/assistant-node.service <<UNIT
[Unit]
Description=Assistant Read-Only Monitoring Node
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=assistant-node
Group=assistant-node
WorkingDirectory=$AGENT
ExecStart=$(command -v python3) $AGENT/agent.py
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=full

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now assistant-node
sleep 1
curl -fsS http://127.0.0.1:9443/health || { systemctl status assistant-node --no-pager; exit 1; }
echo
echo "Read-only monitoring node installed."
echo "Review $AGENT/config.json and expose port 9443 only through a private network or TLS/firewall allowlist."
