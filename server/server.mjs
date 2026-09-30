import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { BiyokBrain } from './lib/biyok.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
loadEnv(path.join(__dirname, '.env'));

const VERSION = '1.1.0';
const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const API_KEY = process.env.OPENAI_API_KEY || '';
const APP_TOKEN = process.env.ASSISTANT_APP_TOKEN || '';
const TEXT_MODEL = process.env.BIYOK_TEXT_MODEL || process.env.OPENAI_MODEL || 'gpt-5.6-luna';
const TRANSCRIPTION_MODEL = process.env.BIYOK_TRANSCRIPTION_MODEL || 'gpt-transcribe';
const CONFIG_FILE = process.env.BIYOK_CONFIG_FILE || path.join(__dirname, 'config', 'biyok.json');

const brain = new BiyokBrain({
  apiKey:API_KEY,
  textModel:TEXT_MODEL,
  transcriptionModel:TRANSCRIPTION_MODEL,
  configFile:CONFIG_FILE
});

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);

    if (req.method === 'GET' && url.pathname === '/health') {
      return json(res, 200, {
        ok:true,
        version:VERSION,
        service:'biyok',
        openaiConfigured:Boolean(API_KEY),
        textModel:TEXT_MODEL,
        transcriptionModel:TRANSCRIPTION_MODEL
      });
    }

    // Public because it contains only tuning parameters, never secrets.
    if (req.method === 'GET' && url.pathname === '/v1/biyok/config') {
      return json(res, 200, brain.publicConfig());
    }

    if (url.pathname.startsWith('/v1/')) ensureAuthorized(req);

    if (req.method === 'GET' && url.pathname === '/v1/biyok/ping') {
      return json(res, 200, { ok:true, version:VERSION, openaiConfigured:Boolean(API_KEY) });
    }

    if (req.method === 'POST' && url.pathname === '/v1/biyok/wake-check') {
      ensureOpenAI();
      const body = await readJson(req);
      return json(res, 200, await brain.verifyWake(body));
    }

    if (req.method === 'POST' && url.pathname === '/v1/biyok/command') {
      ensureOpenAI();
      const body = await readJson(req);
      return json(res, 200, await brain.understandCommand(body));
    }

    return json(res, 404, { error:'not found' });
  } catch (e) {
    const status = e instanceof HttpError ? e.status : mapErrorStatus(e);
    if (status >= 500) console.error(e);
    return json(res, status, { error:e?.message || String(e) });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Biyok server v${VERSION} listening on http://${HOST}:${PORT}`);
  console.log(`Text model=${TEXT_MODEL} transcription=${TRANSCRIPTION_MODEL}`);
});

function ensureAuthorized(req) {
  if (!APP_TOKEN) throw new HttpError(503, 'ASSISTANT_APP_TOKEN is not configured');
  const provided = String(req.headers['x-assistant-token'] || '');
  const a = Buffer.from(provided);
  const b = Buffer.from(APP_TOKEN);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) throw new HttpError(401, 'unauthorized Biyok client');
}

function ensureOpenAI() {
  if (!API_KEY) throw new HttpError(503, 'OPENAI_API_KEY is not configured');
}

function mapErrorStatus(e) {
  const m = String(e?.message || '');
  if (m.includes('required') || m.includes('invalid') || m.includes('too long')) return 400;
  if (m.startsWith('OpenAI ')) return 502;
  return 500;
}

function json(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    'Content-Type':'application/json; charset=utf-8',
    'Content-Length':Buffer.byteLength(body),
    'Cache-Control':'no-store'
  });
  res.end(body);
}

async function readJson(req) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of req) {
    bytes += chunk.length;
    if (bytes > 3_000_000) throw new HttpError(413, 'request too large');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
  } catch {
    throw new HttpError(400, 'invalid JSON');
  }
}

function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const i = line.indexOf('=');
    if (i < 1) continue;
    const k = line.slice(0, i).trim();
    let v = line.slice(i + 1).trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    if (!(k in process.env)) process.env[k] = v;
  }
}

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
