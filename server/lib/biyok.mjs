import fs from 'node:fs';
import path from 'node:path';

export class BiyokBrain {
  constructor({ apiKey, textModel, transcriptionModel, configFile }) {
    this.apiKey = apiKey;
    this.textModel = textModel || 'gpt-5.6-luna';
    this.transcriptionModel = transcriptionModel || 'gpt-transcribe';
    this.configFile = configFile;
  }

  publicConfig() {
    const raw = this.#loadConfig();
    return {
      version: Number(raw.version || 1),
      wake: {
        localStrongScore: num(raw?.wake?.localStrongScore, 0.64, 0.2, 2.0),
        serverVerifyMaxScore: num(raw?.wake?.serverVerifyMaxScore, 1.08, 0.2, 2.5),
        minStartRms: num(raw?.wake?.minStartRms, 95, 20, 5000),
        startNoiseMultiplier: num(raw?.wake?.startNoiseMultiplier, 1.55, 1.05, 5),
        minEndRms: num(raw?.wake?.minEndRms, 75, 20, 5000),
        endNoiseMultiplier: num(raw?.wake?.endNoiseMultiplier, 1.22, 1.01, 5),
        silenceMs: Math.round(num(raw?.wake?.silenceMs, 260, 100, 1500)),
        maxSegmentMs: Math.round(num(raw?.wake?.maxSegmentMs, 2800, 800, 6000)),
        serverVerification: raw?.wake?.serverVerification !== false
      },
      command: {
        minStartRms: num(raw?.command?.minStartRms, 85, 20, 5000),
        startNoiseMultiplier: num(raw?.command?.startNoiseMultiplier, 1.45, 1.05, 5),
        minEndRms: num(raw?.command?.minEndRms, 70, 20, 5000),
        endNoiseMultiplier: num(raw?.command?.endNoiseMultiplier, 1.18, 1.01, 5),
        startTimeoutMs: Math.round(num(raw?.command?.startTimeoutMs, 8000, 2000, 20000)),
        silenceMs: Math.round(num(raw?.command?.silenceMs, 2200, 600, 6000)),
        maxMs: Math.round(num(raw?.command?.maxMs, 30000, 5000, 60000)),
        preRollMs: Math.round(num(raw?.command?.preRollMs, 260, 0, 1000))
      }
    };
  }

  async verifyWake({ audioBase64, sampleRate = 16000 }) {
    this.#ensureConfigured();
    const pcm = decodePcm(audioBase64, sampleRate, 6);
    const transcript = (await this.#transcribe(pcm, sampleRate)).trim();
    const normalized = normalizePersian(transcript).replace(/\s+/g, '');
    const accepted = normalized.includes('بیوک') || normalized.includes('بيوك');
    return { accepted, transcript };
  }

  async understandCommand({ audioBase64, sampleRate = 16000, deviceNow = '', timeZone = 'Asia/Tehran' }) {
    this.#ensureConfigured();
    const pcm = decodePcm(audioBase64, sampleRate, 45);
    const transcript = (await this.#transcribe(pcm, sampleRate)).trim();
    if (!transcript) return { transcript:'', command:{ action:'unknown', title:'', remindAt:'', confidence:0 } };
    let command;
    try {
      command = await this.#interpret(transcript, deviceNow, timeZone);
    } catch {
      command = fallbackCommand(transcript);
    }
    return { transcript, command };
  }

  #loadConfig() {
    try {
      return JSON.parse(fs.readFileSync(this.configFile, 'utf8'));
    } catch {
      return {};
    }
  }

  #ensureConfigured() {
    if (!this.apiKey) throw new Error('OPENAI_API_KEY is not configured');
  }

  async #transcribe(pcm, sampleRate) {
    const wav = pcm16MonoToWav(pcm, sampleRate);
    const form = new FormData();
    form.append('model', this.transcriptionModel);
    form.append('file', new Blob([wav], { type:'audio/wav' }), 'biyok.wav');
    const r = await fetch('https://api.openai.com/v1/audio/transcriptions', {
      method:'POST',
      headers:{ Authorization:`Bearer ${this.apiKey}` },
      body:form
    });
    const text = await r.text();
    if (!r.ok) throw new Error(`OpenAI transcription ${r.status}: ${text}`);
    const parsed = JSON.parse(text);
    return String(parsed.text || '');
  }

  async #interpret(transcript, deviceNow, timeZone) {
    const schema = {
      type:'object',
      additionalProperties:false,
      properties:{
        action:{ type:'string', enum:['create_reminder','list_reminders','unknown'] },
        title:{ type:'string' },
        remindAt:{ type:'string' },
        confidence:{ type:'number', minimum:0, maximum:1 }
      },
      required:['action','title','remindAt','confidence']
    };
    const instructions = [
      'You are the intent parser for a Persian personal reminder app named Biyok.',
      'The user speaks colloquial Iranian Persian. Return only the requested structured output.',
      'Use create_reminder when the user asks to remember, do, buy, call, check, or otherwise keep a task.',
      'Use list_reminders when the user asks what tasks/reminders they have.',
      'For create_reminder, title must be the clean task text without filler such as یادم بنداز.',
      'If a time/date is explicitly or relatively stated, resolve it against DEVICE_NOW and TIME_ZONE and return an RFC3339 timestamp with an explicit offset in remindAt.',
      'If no time/date was stated, remindAt must be an empty string. Never invent a time.',
      'For list_reminders or unknown, title and remindAt must be empty strings.'
    ].join('\n');
    const payload = {
      model:this.textModel,
      instructions,
      input:`DEVICE_NOW=${deviceNow || new Date().toISOString()}\nTIME_ZONE=${timeZone}\nTRANSCRIPT=${transcript}`,
      reasoning:{ effort:'low' },
      store:false,
      text:{ format:{ type:'json_schema', name:'biyok_command', strict:true, schema } }
    };
    const r = await fetch('https://api.openai.com/v1/responses', {
      method:'POST',
      headers:{ Authorization:`Bearer ${this.apiKey}`, 'Content-Type':'application/json' },
      body:JSON.stringify(payload)
    });
    const text = await r.text();
    if (!r.ok) throw new Error(`OpenAI command ${r.status}: ${text}`);
    const response = JSON.parse(text);
    const out = [];
    for (const item of response.output || []) {
      if (item.type !== 'message') continue;
      for (const c of item.content || []) if (c.type === 'output_text' && c.text) out.push(c.text);
    }
    if (!out.length) throw new Error('empty structured command');
    return JSON.parse(out.join(''));
  }
}

function fallbackCommand(transcript) {
  const t = normalizePersian(transcript);
  if (['چه کارهایی','کارهای من','یادآوری های من','یادآوری هام'].some(x => t.includes(x))) {
    return { action:'list_reminders', title:'', remindAt:'', confidence:0.5 };
  }
  return { action:'create_reminder', title:transcript.trim(), remindAt:'', confidence:0.25 };
}

function decodePcm(raw, sampleRate, maxSeconds) {
  if (!raw || typeof raw !== 'string') throw new Error('audioBase64 is required');
  const rate = Number(sampleRate);
  if (!Number.isFinite(rate) || rate < 8000 || rate > 48000) throw new Error('invalid sampleRate');
  const pcm = Buffer.from(raw, 'base64');
  if (!pcm.length || pcm.length % 2) throw new Error('invalid PCM audio');
  if (pcm.length > rate * 2 * maxSeconds) throw new Error('audio is too long');
  return pcm;
}

function pcm16MonoToWav(pcm, sampleRate) {
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

function normalizePersian(s) {
  return String(s || '')
    .replace(/ي/g, 'ی')
    .replace(/ك/g, 'ک')
    .replace(/[\u200c\u200f]/g, ' ')
    .replace(/[،,.!?؟؛:«»"'()\-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function num(value, fallback, min, max) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
}
