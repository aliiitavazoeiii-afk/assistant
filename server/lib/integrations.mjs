import fs from 'node:fs';

export class Integrations {
  constructor(configFile, store) { this.configFile = configFile; this.store = store; }
  config() { try { return JSON.parse(fs.readFileSync(this.configFile, 'utf8')); } catch { return { integrations: {} }; } }
  list() { const all = this.config().integrations || {}; return Object.entries(all).map(([name, c]) => ({ name, operations: Object.keys(c.operations || {}) })); }
  async call(name, operation, params = {}) {
    const c = (this.config().integrations || {})[name]; if (!c) throw new Error(`integration not found: ${name}`);
    const op = (c.operations || {})[operation]; if (!op) throw new Error(`operation not allowed: ${name}.${operation}`);
    if ((op.risk || 'read') !== 'read' && String(process.env.ASSISTANT_ALLOW_INTEGRATION_WRITES || '').toLowerCase() !== 'true') throw new Error('integration write actions are disabled');
    const token = c.tokenEnv ? process.env[c.tokenEnv] : '';
    let p = op.path; for (const [k, v] of Object.entries(params)) p = p.replaceAll(`{${k}}`, encodeURIComponent(String(v)));
    const url = new URL(p, c.baseUrl);
    if ((op.method || 'GET').toUpperCase() === 'GET') for (const [k, v] of Object.entries(params)) if (!op.path.includes(`{${k}}`)) url.searchParams.set(k, String(v));
    const headers = { Accept: 'application/json', ...(c.headers || {}) };
    if (token) headers[c.tokenHeader || 'Authorization'] = c.tokenPrefix === '' ? token : `${c.tokenPrefix || 'Bearer '}${token}`;
    const method = (op.method || 'GET').toUpperCase(); const init = { method, headers };
    if (method !== 'GET' && method !== 'HEAD') { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(params); }
    const r = await fetch(url, init); const text = await r.text(); if (!r.ok) throw new Error(`${name}.${operation} ${r.status}: ${text}`);
    this.store?.audit('integration_call', { name, operation, ok: true }); try { return JSON.parse(text); } catch { return { text }; }
  }
}
