#!/usr/bin/env python3
import argparse, json, os
from datetime import datetime, timedelta, timezone
from pathlib import Path

p = argparse.ArgumentParser(); p.add_argument('--days', type=int, default=0); args = p.parse_args()
file = Path(os.getenv('VPN_USERS_FILE', '/etc/assistant-node/vpn-users.json'))
if not file.exists(): raise SystemExit(f'VPN_USERS_FILE not found: {file}')
data = json.loads(file.read_text()); users = data.get('users', data if isinstance(data, list) else [])
now = datetime.now(timezone.utc); end = now + timedelta(days=max(0,args.days) + 1); out = []
for u in users:
    if not u.get('enabled', True): continue
    raw = u.get('expiresAt') or u.get('expireAt')
    if not raw: continue
    try: exp = datetime.fromisoformat(str(raw).replace('Z','+00:00'))
    except Exception: continue
    if exp.tzinfo is None: exp = exp.replace(tzinfo=timezone.utc)
    if now <= exp < end:
        out.append({'id': str(u.get('id') or u.get('username') or ''), 'name': u.get('name') or u.get('username') or '', 'username': u.get('username') or '', 'recipient': u.get('recipient') or u.get('phone') or u.get('telegram') or '', 'expiresAt': exp.isoformat(), 'server': u.get('server') or os.uname().nodename})
print(json.dumps({'users': out, 'daysAhead': args.days}, ensure_ascii=False))
