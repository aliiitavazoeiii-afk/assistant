import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
loadEnv(path.join(__dirname, ".env"));

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || "0.0.0.0";
const MODEL = process.env.OPENAI_MODEL || "gpt-5.6";
const API_KEY = process.env.OPENAI_API_KEY || "";
const STORE = (process.env.OPENAI_STORE_RESPONSES || "true").toLowerCase() !== "false";
const APP_TOKEN = process.env.ASSISTANT_APP_TOKEN || "";
const SAFETY_ID = crypto.createHash("sha256").update(process.env.ASSISTANT_USER_ID || "personal-assistant").digest("hex");

const INSTRUCTIONS = `
You are Ali's private Persian-first Android personal assistant.

LANGUAGE AND STYLE
- Understand Persian naturally, including colloquial Persian and Persian written in Latin characters.
- Reply in Persian unless the user asks otherwise.
- Keep spoken replies concise and practical.

TOOL BEHAVIOR
- Never claim an action succeeded unless a tool output confirms success.
- Use device tools whenever the user asks you to act on the phone rather than merely explaining how.
- If a contact or target is ambiguous, ask the user instead of choosing randomly.
- For relative times such as "tomorrow at 6", resolve them using the deviceContext supplied with the user's message and emit RFC3339 timestamps with an explicit offset.
- Treat "idea" requests as save_note with category "ideas" unless the user gives another category.
- Treat "remind me" as set_reminder. Treat "wake me" as set_alarm.
- If the user asks for a strict alarm and does not specify a challenge, use SELFIE.

TRANSACTION BOUNDARY
- Opening a shopping app is allowed, but transaction checkout and authentication remain manual.
- Never request, store, or submit payment or authentication secrets.

PRIVACY
- Retrieve only the SMS/notifications/notes needed for the user's request. Avoid broad dumps when a narrower query works.
`.trim();

const TOOLS = [
  fn("save_note", "Save a durable local note or idea on the Android device.", {
    category: str("Category such as ideas, work, vpn, darma, personal"),
    title: str("Short title"),
    content: str("Full note content")
  }, ["category", "title", "content"]),

  fn("list_notes", "Read local notes/ideas saved by the assistant.", {
    category: str("Category filter; empty string means any"),
    query: str("Text search; empty string means any"),
    limit: int("Maximum number of notes, 1-100")
  }, ["category", "query", "limit"]),

  fn("set_alarm", "Create an exact Android alarm. Use for waking the user.", {
    timestamp: str("RFC3339 timestamp with explicit timezone offset"),
    label: str("Alarm label"),
    song_query: str("Local song title/artist search, or empty string for default alarm sound"),
    strict: bool("Whether dismiss requires completing a challenge"),
    challenge: enumStr(["NONE", "SELFIE"], "Strict alarm challenge")
  }, ["timestamp", "label", "song_query", "strict", "challenge"]),

  fn("set_reminder", "Create an exact spoken reminder on the Android device.", {
    timestamp: str("RFC3339 timestamp with explicit timezone offset"),
    label: str("What to remind Ali about")
  }, ["timestamp", "label"]),

  fn("call_contact", "Start a phone call to a named contact.", { name: str("Contact display name") }, ["name"]),
  fn("send_sms", "Send an SMS to a named contact or phone number.", {
    recipient: str("Contact name or phone number"), message: str("Exact message to send")
  }, ["recipient", "message"]),
  fn("read_sms", "Read SMS messages in a time range, optionally filtered by text.", {
    start_iso: str("RFC3339 start timestamp, or empty string"), end_iso: str("RFC3339 end timestamp, or empty string"),
    query: str("Body text filter, or empty string"), limit: int("Maximum messages, 1-100")
  }, ["start_iso", "end_iso", "query", "limit"]),
  fn("recent_notifications", "Read recent notifications captured after Notification Access was enabled.", {
    query: str("Search title/text/package; empty string means any"), limit: int("Maximum notifications, 1-100")
  }, ["query", "limit"]),
  fn("get_current_location", "Get the device's most recent Android location fix.", {}, []),
  fn("open_maps", "Open turn-by-turn Google Maps navigation to a destination.", { destination: str("Destination place/address/search query") }, ["destination"]),
  fn("open_app", "Open an installed Android app by label or package fragment.", { name: str("App name such as SnappFood, Spotify, Chrome") }, ["name"])
];

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/health") return json(res, 200, { ok: true, model: MODEL });
    if (req.method === "POST" && req.url === "/v1/agent/turn") {
      ensureAuthorized(req); ensureConfigured();
      const body = await readJson(req); const message = String(body.message || "").trim();
      if (!message) return json(res, 400, { error: "message is required" });
      const deviceContext = body.deviceContext || {};
      const response = await openaiResponse({ input: [{ role: "user", content: `DEVICE_CONTEXT=${JSON.stringify(deviceContext)}\n\nUSER_REQUEST=${message}` }] });
      return json(res, 200, simplify(response));
    }
    if (req.method === "POST" && req.url === "/v1/agent/continue") {
      ensureAuthorized(req); ensureConfigured();
      const body = await readJson(req); const previousResponseId = String(body.previousResponseId || "");
      const toolOutputs = Array.isArray(body.toolOutputs) ? body.toolOutputs : [];
      if (!previousResponseId) return json(res, 400, { error: "previousResponseId is required" });
      const input = toolOutputs.map(x => ({ type: "function_call_output", call_id: String(x.callId), output: String(x.output ?? "") }));
      const response = await openaiResponse({ previous_response_id: previousResponseId, input });
      return json(res, 200, simplify(response));
    }
    return json(res, 404, { error: "not found" });
  } catch (error) {
    console.error(error); return json(res, 500, { error: error?.message || String(error) });
  }
});

server.listen(PORT, HOST, () => console.log(`Assistant server listening on http://${HOST}:${PORT} using ${MODEL}`));

async function openaiResponse(extra) {
  const payload = { model: MODEL, instructions: INSTRUCTIONS, tools: TOOLS, parallel_tool_calls: true, store: STORE, safety_identifier: SAFETY_ID, ...extra };
  const r = await fetch("https://api.openai.com/v1/responses", {
    method: "POST", headers: { "Authorization": `Bearer ${API_KEY}`, "Content-Type": "application/json" }, body: JSON.stringify(payload)
  });
  const text = await r.text(); if (!r.ok) throw new Error(`OpenAI ${r.status}: ${text}`); return JSON.parse(text);
}

function simplify(response) {
  const toolCalls = []; const textParts = [];
  for (const item of response.output || []) {
    if (item.type === "function_call") toolCalls.push({ callId: item.call_id, name: item.name, arguments: item.arguments || "{}" });
    if (item.type === "message") for (const c of item.content || []) if (c.type === "output_text" && c.text) textParts.push(c.text);
  }
  return { responseId: response.id, text: textParts.join("\n").trim(), toolCalls };
}

function fn(name, description, properties, required) { return { type: "function", name, description, parameters: { type: "object", properties, required, additionalProperties: false } }; }
function str(description) { return { type: "string", description }; }
function int(description) { return { type: "integer", minimum: 1, maximum: 100, description }; }
function bool(description) { return { type: "boolean", description }; }
function enumStr(values, description) { return { type: "string", enum: values, description }; }

function ensureAuthorized(req) {
  if (!APP_TOKEN) return;
  const provided = String(req.headers["x-assistant-token"] || ""); const a = Buffer.from(provided); const b = Buffer.from(APP_TOKEN);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) throw new Error("unauthorized assistant client");
}
function ensureConfigured() { if (!API_KEY) throw new Error("OPENAI_API_KEY is not configured in server/.env"); }
function json(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Content-Length": Buffer.byteLength(body), "Cache-Control": "no-store" }); res.end(body);
}
async function readJson(req) {
  const chunks = []; let bytes = 0;
  for await (const chunk of req) { bytes += chunk.length; if (bytes > 2_000_000) throw new Error("request too large"); chunks.push(chunk); }
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}
function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const raw of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const line = raw.trim(); if (!line || line.startsWith("#")) continue; const i = line.indexOf("="); if (i < 1) continue;
    const key = line.slice(0, i).trim(); let value = line.slice(i + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    if (!(key in process.env)) process.env[key] = value;
  }
}
