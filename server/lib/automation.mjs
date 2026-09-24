export class AutomationEngine {
  constructor({ store, nodes, integrations }) { this.store = store; this.nodes = nodes; this.integrations = integrations; this.timer = null; this.running = new Set(); }
  start() { if (!this.timer) { this.timer = setInterval(() => this.tick().catch(() => {}), 30_000); this.timer.unref?.(); } }
  async tick() { const now = new Date(); for (const rule of this.store.listAutomations()) { if (!rule.enabled || this.running.has(rule.id) || !isDue(rule, now)) continue; await this.run(rule.id).catch(e => this.store.addReport({ type:'automation_error', ruleId:rule.id, error:e.message })); } }
  async run(id) {
    const rule = this.store.listAutomations().find(x => x.id === id); if (!rule) throw new Error(`automation not found: ${id}`); if (this.running.has(id)) return { skipped:'already-running' };
    this.running.add(id);
    try {
      let result;
      if (rule.type === 'node_health') result = await this.nodeHealth(rule);
      else if (rule.type === 'vpn_expiry') result = await this.vpnExpiry(rule);
      else if (rule.type === 'integration_snapshot') result = await this.integrationSnapshot(rule);
      else throw new Error(`unsupported automation type: ${rule.type}`);
      rule.lastRunAt = new Date().toISOString(); this.store.saveAutomation(rule);
      const report = this.store.addReport({ type:rule.type, ruleId:id, result }); this.store.audit('automation_run', { ruleId:id, type:rule.type, ok:true }); return report;
    } catch (e) { this.store.audit('automation_run', { ruleId:id, type:rule.type, ok:false, error:e.message }); throw e; }
    finally { this.running.delete(id); }
  }
  async nodeHealth(rule) { const ids = rule.nodeIds?.length ? rule.nodeIds : this.nodes.list().map(x => x.id); const out=[]; for (const id of ids) { try { out.push({ nodeId:id, ok:true, data:await this.nodes.call(id,'status',{}) }); } catch(e) { out.push({ nodeId:id, ok:false, error:e.message }); } } return out; }
  async vpnExpiry(rule) { const daysAhead=Number(rule.daysAhead ?? 0), out=[]; for (const id of rule.nodeIds || []) { try { const data=await this.nodes.call(id,'vpn_expiring',{daysAhead}), users=data.users || data.result?.users || []; const sent=rule.sendMessages ? await this.sendRenewals(users,rule.messageTemplate) : []; out.push({nodeId:id,ok:true,users,sent}); } catch(e) { out.push({nodeId:id,ok:false,error:e.message}); } } return out; }
  async integrationSnapshot(rule) { return this.integrations.call(rule.integration, rule.operation, rule.params || {}); }
  async sendRenewals(users, template) {
    if (String(process.env.ASSISTANT_ALLOW_MESSAGES || '').toLowerCase() !== 'true') return users.map(u => ({ id:u.id, skipped:'ASSISTANT_ALLOW_MESSAGES is not true' }));
    const webhook=process.env.ASSISTANT_MESSAGE_WEBHOOK; if (!webhook) return users.map(u => ({ id:u.id, skipped:'message webhook not configured' }));
    const results=[]; for (const u of users) { if (!u.recipient) { results.push({id:u.id,skipped:'no recipient'}); continue; } const message=render(template || 'سلام {name}، اعتبار سرویس شما در {expiresAt} به پایان می‌رسد. برای تمدید لطفاً اقدام کنید.',u); try { const headers={'Content-Type':'application/json'}, token=process.env.ASSISTANT_MESSAGE_WEBHOOK_TOKEN; if(token) headers.Authorization=`Bearer ${token}`; const r=await fetch(webhook,{method:'POST',headers,body:JSON.stringify({recipient:u.recipient,message,user:u})}); if(!r.ok) throw new Error(`webhook ${r.status}: ${await r.text()}`); results.push({id:u.id,recipient:u.recipient,ok:true}); } catch(e) { results.push({id:u.id,recipient:u.recipient,ok:false,error:e.message}); } } return results;
  }
}
function render(t,data){return String(t).replace(/\{([A-Za-z0-9_]+)\}/g,(_,k)=>String(data[k]??''));}
function isDue(rule,now){const last=rule.lastRunAt?new Date(rule.lastRunAt):null,s=rule.schedule||{};if(s.intervalMinutes)return !last||now-last>=Number(s.intervalMinutes)*60_000;if(s.dailyTime){const tz=s.timeZone||process.env.ASSISTANT_TIMEZONE||'UTC',parts=Object.fromEntries(new Intl.DateTimeFormat('en-CA',{timeZone:tz,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(now).map(p=>[p.type,p.value])),key=`${parts.year}-${parts.month}-${parts.day}`,hm=`${parts.hour}:${parts.minute}`,lastKey=last?Object.values(Object.fromEntries(new Intl.DateTimeFormat('en-CA',{timeZone:tz,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(last).filter(p=>['year','month','day'].includes(p.type)).map(p=>[p.type,p.value]))).join('-'):'';return hm>=s.dailyTime&&lastKey!==key;}return false;}
