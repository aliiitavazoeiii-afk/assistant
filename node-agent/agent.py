#!/usr/bin/env python3
import hashlib, hmac, json, os, shutil, ssl, subprocess, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def load_env(path):
    if not Path(path).exists(): return
    for raw in Path(path).read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith('#') or '=' not in line: continue
        k, v = line.split('=', 1); os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))

load_env(os.getenv('ASSISTANT_NODE_ENV', str(ROOT / '.env')))
HOST = os.getenv('AGENT_HOST', '127.0.0.1')
PORT = int(os.getenv('AGENT_PORT', '9443'))
SECRET = os.getenv('AGENT_SECRET', '')
CONFIG_FILE = Path(os.getenv('AGENT_CONFIG', str(ROOT / 'config.json')))
MAX_SKEW = int(os.getenv('AGENT_MAX_CLOCK_SKEW_SECONDS', '120'))

def config():
    try: return json.loads(CONFIG_FILE.read_text())
    except Exception: return {'services': []}

def allowed_service(name): return name in set(config().get('services', []))

def run_fixed(argv, timeout=12):
    cp = subprocess.run(argv, text=True, capture_output=True, timeout=timeout, check=False)
    if cp.returncode != 0: raise RuntimeError(f"command failed ({cp.returncode}): {cp.stderr.strip() or cp.stdout.strip()}")
    return cp.stdout.strip()

def system_status():
    load = Path('/proc/loadavg').read_text().split()[:3] if Path('/proc/loadavg').exists() else []
    mem = {}
    if Path('/proc/meminfo').exists():
        for line in Path('/proc/meminfo').read_text().splitlines():
            if ':' in line:
                k, v = line.split(':', 1); mem[k] = v.strip()
    disk = shutil.disk_usage('/')
    uptime = float(Path('/proc/uptime').read_text().split()[0]) if Path('/proc/uptime').exists() else None
    return {'hostname': os.uname().nodename, 'kernel': os.uname().release, 'load': load, 'uptimeSeconds': uptime, 'memory': {k: mem.get(k) for k in ['MemTotal','MemAvailable','SwapTotal','SwapFree']}, 'disk': {'total': disk.total, 'used': disk.used, 'free': disk.free}, 'time': int(time.time())}

def service_status(name):
    if not allowed_service(name): raise ValueError(f'service not allowlisted: {name}')
    out = run_fixed(['systemctl','show',name,'--no-pager','--property=Id,Description,LoadState,ActiveState,SubState,MainPID,NRestarts'])
    return dict(line.split('=',1) for line in out.splitlines() if '=' in line)

def logs(name, lines):
    if not allowed_service(name): raise ValueError(f'service not allowlisted: {name}')
    n = max(1, min(500, int(lines)))
    return {'service': name, 'lines': run_fixed(['journalctl','-u',name,'-n',str(n),'--no-pager','--output=short-iso'], timeout=20)}

def vpn_expiring(days_ahead):
    cmd = config().get('vpnAdapter')
    if not isinstance(cmd, list) or not cmd: raise ValueError('vpnAdapter is not configured')
    days = max(0, min(365, int(days_ahead)))
    out = run_fixed([str(x) for x in cmd] + ['--days', str(days)], timeout=30)
    value = json.loads(out or '{}')
    if not isinstance(value, dict) or not isinstance(value.get('users', []), list): raise ValueError('VPN adapter must return JSON object with users[]')
    return value

def execute(op, args):
    if op == 'status': return system_status()
    if op == 'service_status': return service_status(str(args.get('service','')))
    if op == 'logs': return logs(str(args.get('service','')), args.get('lines',100))
    if op == 'vpn_expiring': return vpn_expiring(args.get('daysAhead',0))
    raise ValueError(f'operation not allowed: {op}')

class Handler(BaseHTTPRequestHandler):
    server_version = 'AssistantNode/0.2'
    def log_message(self, fmt, *args): print(f"{self.client_address[0]} - {fmt % args}")
    def send_json(self, status, value):
        raw = json.dumps(value, ensure_ascii=False).encode(); self.send_response(status); self.send_header('Content-Type','application/json; charset=utf-8'); self.send_header('Content-Length',str(len(raw))); self.send_header('Cache-Control','no-store'); self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        if self.path == '/health': return self.send_json(200, {'ok': True, 'version': '0.2.0', 'mode': 'read-only'})
        self.send_json(404, {'error':'not found'})
    def do_POST(self):
        try:
            if self.path != '/v1/query': return self.send_json(404, {'error':'not found'})
            if not SECRET: return self.send_json(503, {'error':'AGENT_SECRET is not configured'})
            length = int(self.headers.get('Content-Length','0'))
            if length < 0 or length > 1_000_000: return self.send_json(413, {'error':'request too large'})
            body = self.rfile.read(length)
            ts = self.headers.get('X-Assistant-Timestamp',''); sig = self.headers.get('X-Assistant-Signature','')
            try: ts_int = int(ts)
            except Exception: return self.send_json(401, {'error':'invalid timestamp'})
            if abs(int(time.time()) - ts_int) > MAX_SKEW: return self.send_json(401, {'error':'request timestamp outside allowed window'})
            expected = hmac.new(SECRET.encode(), f"{ts}.POST.{self.path}.".encode() + body, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(sig, expected): return self.send_json(401, {'error':'bad signature'})
            req = json.loads(body or b'{}'); op = str(req.get('operation','')); args = req.get('args') or {}
            result = execute(op, args); self.send_json(200, {'ok': True, 'operation': op, 'result': result, **(result if isinstance(result, dict) else {})})
        except Exception as e: self.send_json(400, {'ok': False, 'error': str(e)})

if __name__ == '__main__':
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    cert, key = os.getenv('AGENT_TLS_CERT',''), os.getenv('AGENT_TLS_KEY','')
    if cert and key:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(cert, key); httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True); scheme = 'https'
    else: scheme = 'http'
    print(f'Assistant read-only node listening on {scheme}://{HOST}:{PORT}')
    httpd.serve_forever()
