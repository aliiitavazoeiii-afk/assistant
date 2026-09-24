#!/usr/bin/env python3
import argparse, json, os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

p = argparse.ArgumentParser(); p.add_argument('--days', type=int, default=0); args = p.parse_args()
file = Path(os.getenv('VPN_USERS_FILE', '/etc/assistant-node/vpn-users.json'))
if not file.exists(): raise SystemExit(f'VPN_USERS_FILE not found: {file}')
data = json.loads(file.read_text()); users = data.get('users', data if isinstance(data, list) else [])
zone = ZoneInfo(os.getenv('VPN_TIMEZONE', 'Asia/Tehran'))
local_now = datetime.now(zone)
start_local = local_now.replace(hour=0, minute=0, second=0, microsecond=0)
end_local = start_local + timedelta(days=max(0, args.days) + 1)
start = start_local.astimezone(timezone.utc); end = end_local.astimezone(timezone.utc)
out = []
for u in users:
    if not u.get('enabled', True): continue
    raw = u.get('expiresAt') or u.get('expireAt')
    if not raw: continue
    try: exp = datetime.fromisoformat(str(raw).replace('Z','+00:00'))
    except Exception: continue
    if exp.tzinfo is None: exp = exp.replace(tzinfo=zone)
    exp_utc = exp.astimezone(timezone.utc)
    if start <= exp_utc < end:
        out.append({'id': str(u.get('id') or u.get('username') or ''), 'name': u.get('name') or u.get('username') or '', 'username': u.get('username') or '', 'recipient': u.get('recipient') or u.get('phone') or u.get('telegram') or '', 'expiresAt': exp.isoformat(), 'server': u.get('server') or os.uname().nodename})
print(json.dumps({'users': out, 'daysAhead': args.days, 'timeZone': str(zone)}, ensure_ascii=False))
