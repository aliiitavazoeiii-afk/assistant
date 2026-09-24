import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export class JsonStore {
  constructor(root) { this.root = root; fs.mkdirSync(root, { recursive: true }); }
  file(name) { return path.join(this.root, name); }
  read(name, fallback) { try { return JSON.parse(fs.readFileSync(this.file(name), 'utf8')); } catch { return structuredClone(fallback); } }
  write(name, value) { const file = this.file(name); const tmp = `${file}.${process.pid}.tmp`; fs.writeFileSync(tmp, JSON.stringify(value, null, 2)); fs.renameSync(tmp, file); }
  appendJsonl(name, value) { fs.appendFileSync(this.file(name), `${JSON.stringify(value)}\n`); }
  getSession(id) { return this.read('sessions.json', {})[id] || null; }
  setSession(id, patch) { if (!id) return; const all = this.read('sessions.json', {}); all[id] = { ...(all[id] || {}), ...patch, updatedAt: new Date().toISOString() }; this.write('sessions.json', all); }
  addMemory({ category = 'general', title = '', content, tags = [] }) { const all = this.read('memories.json', []); const item = { id: crypto.randomUUID(), category, title, content, tags, createdAt: new Date().toISOString() }; all.unshift(item); this.write('memories.json', all.slice(0, 5000)); return item; }
  searchMemory(query = '', category = '', limit = 20) { const q = query.trim().toLowerCase(); const c = category.trim().toLowerCase(); return this.read('memories.json', []).filter(x => { const hay = `${x.title || ''} ${x.content || ''} ${(x.tags || []).join(' ')}`.toLowerCase(); return (!q || hay.includes(q)) && (!c || String(x.category).toLowerCase() === c); }).slice(0, Math.max(1, Math.min(100, limit))); }
  addTask(input) { const all = this.read('tasks.json', []); const item = { id: crypto.randomUUID(), status: 'OPEN', priority: 'NORMAL', createdAt: new Date().toISOString(), ...input }; all.unshift(item); this.write('tasks.json', all); return item; }
  listTasks({ status = '', project = '', limit = 50 } = {}) { return this.read('tasks.json', []).filter(x => (!status || x.status === status) && (!project || x.project === project)).slice(0, limit); }
  completeTask(id) { const all = this.read('tasks.json', []); const item = all.find(x => x.id === id); if (!item) throw new Error(`task not found: ${id}`); item.status = 'DONE'; item.completedAt = new Date().toISOString(); this.write('tasks.json', all); return item; }
  setGoal(input) { const all = this.read('goals.json', []); const item = { id: crypto.randomUUID(), createdAt: new Date().toISOString(), ...input }; all.unshift(item); this.write('goals.json', all); return item; }
  listGoals(limit = 50) { return this.read('goals.json', []).slice(0, limit); }
  listAutomations() { return this.read('automations.json', []); }
  saveAutomation(rule) { const all = this.listAutomations(); const now = new Date().toISOString(); const item = { id: rule.id || crypto.randomUUID(), enabled: true, createdAt: rule.createdAt || now, ...rule, updatedAt: now }; const i = all.findIndex(x => x.id === item.id); if (i >= 0) all[i] = item; else all.push(item); this.write('automations.json', all); return item; }
  addReport(report) { const all = this.read('reports.json', []); const item = { id: crypto.randomUUID(), at: new Date().toISOString(), ...report }; all.unshift(item); this.write('reports.json', all.slice(0, 1000)); return item; }
  listReports(limit = 50) { return this.read('reports.json', []).slice(0, Math.max(1, Math.min(200, limit))); }
  audit(event, data = {}) { this.appendJsonl('audit.jsonl', { at: new Date().toISOString(), event, ...data }); }
  recentAudit(limit = 50) { try { return fs.readFileSync(this.file('audit.jsonl'), 'utf8').trim().split('\n').filter(Boolean).slice(-limit).reverse().map(x => JSON.parse(x)); } catch { return []; } }
}
