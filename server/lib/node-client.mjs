import fs from 'node:fs';
import crypto from 'node:crypto';

export class NodeClient {
  constructor(configFile, store) { this.configFile = configFile; this.store = store; }
  nodes() { try { return JSON.parse(fs.readFileSync(this.configFile, 'utf8')).nodes || []; } catch { return []; } }
  list() { return this.nodes().map(({ secretEnv, ...x }) => ({ ...x, configured: Boolean(process.env[secretEnv || '']) })); }
  get(id) { const node = this.nodes().find(x => x.id === id); if (!node) throw new Error(`node not found: ${id}`); return node; }
  async call(id, operation, args = {}) {
    const node = this.get(id); const secret = process.env[node.secretEnv || '']; if (!secret) throw new Error(`missing node secret env: ${node.secretEnv}`);
    const p = '/v1/query'; const body = JSON.stringify({ operation, args }); const timestamp = String(Math.floor(Date.now() / 1000));
    const signature = crypto.createHmac('sha256', secret).update(`${timestamp}.POST.${p}.${body}`).digest('hex');
    const ctrl = new AbortController(); const timer = setTimeout(() => ctrl.abort(), node.timeoutMs || 12000);
    try {
      const r = await fetch(`${String(node.url).replace(/\/$/, '')}${p}`, { method:'POST', signal:ctrl.signal, headers:{ 'Content-Type':'application/json', 'X-Assistant-Timestamp':timestamp, 'X-Assistant-Signature':signature }, body });
      const text = await r.text(); if (!r.ok) throw new Error(`node ${id} ${r.status}: ${text}`);
      const value = JSON.parse(text); this.store?.audit('node_query', { nodeId:id, operation, ok:true }); return value;
    } catch (e) { this.store?.audit('node_query', { nodeId:id, operation, ok:false, error:e.message }); throw e; }
    finally { clearTimeout(timer); }
  }
}
