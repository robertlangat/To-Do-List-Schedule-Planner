// To-Do Planner AI backend.
// Keeps the model provider's API key server-side, validates what the app sends, asks the model
// for a structured plan, validates that too, and returns JSON the app validates again.
// No dependencies: Node.js 20+ (uses built-in fetch and node:http).
//
// Environment (see backend/.env.example):
//   PORT=8787
//   AI_PROVIDER=anthropic | mock        (mock = deterministic offline responses for testing)
//   ANTHROPIC_API_KEY=...               (required when AI_PROVIDER=anthropic)
//   AI_MODEL=claude-sonnet-5-5
//   APP_SHARED_TOKEN=...                (optional; if set, requests need "Authorization: Bearer <token>")
//   RATE_LIMIT_PER_MINUTE=20
//   UPSTREAM_TIMEOUT_MS=30000

import http from 'node:http';
import { pathToFileURL } from 'node:url';

const MAX_BODY = 32 * 1024;
const MAX_INPUT = 4000;

export function config(env = process.env) {
  return {
    port: Number(env.PORT || 8787),
    provider: (env.AI_PROVIDER || 'mock').toLowerCase(),
    apiKey: env.ANTHROPIC_API_KEY || '',
    model: env.AI_MODEL || 'claude-sonnet-5-5',
    sharedToken: env.APP_SHARED_TOKEN || '',
    ratePerMinute: Number(env.RATE_LIMIT_PER_MINUTE || 20),
    timeoutMs: Number(env.UPSTREAM_TIMEOUT_MS || 30000),
  };
}

const isoDate = /^\d{4}-\d{2}-\d{2}$/;
const isoDateTime = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;

/** Validates the app's request. Returns { value } or { error }. */
export function validateRequest(body) {
  if (!body || typeof body !== 'object') return { error: 'Body must be a JSON object' };
  const { input, date, timezone, availability = [], busy = [] } = body;
  if (typeof input !== 'string' || !input.trim()) return { error: 'input is required' };
  if (input.length > MAX_INPUT) return { error: `input must be at most ${MAX_INPUT} characters` };
  if (typeof date !== 'string' || !isoDate.test(date)) return { error: 'date must be YYYY-MM-DD' };
  if (timezone != null && (typeof timezone !== 'string' || timezone.length > 64)) return { error: 'timezone is invalid' };
  const win = (w) => w && typeof w === 'object' && isoDateTime.test(w.start) && isoDateTime.test(w.end);
  if (!Array.isArray(availability) || availability.length > 10 || !availability.every(win)) return { error: 'availability is invalid' };
  if (!Array.isArray(busy) || busy.length > 50 || !busy.every(win)) return { error: 'busy is invalid' };
  return {
    value: {
      input: input.trim(), date, timezone: timezone || 'UTC',
      availability: availability.map((w) => ({ start: w.start, end: w.end })),
      busy: busy.map((b) => ({ start: b.start, end: b.end, title: String(b.title || 'Busy').slice(0, 120) })),
    },
  };
}

/** Normalises a model response into the shape the app expects; throws if unusable. */
export function sanitizePlan(raw, model) {
  if (!raw || typeof raw !== 'object' || !Array.isArray(raw.tasks)) throw new Error('Model response had no tasks array');
  const priorities = new Set(['high', 'medium', 'low', 'none']);
  const tasks = raw.tasks.slice(0, 30).flatMap((t) => {
    if (!t || typeof t !== 'object' || typeof t.title !== 'string' || !t.title.trim()) return [];
    const out = { title: t.title.trim().slice(0, 200) };
    if (typeof t.notes === 'string' && t.notes.trim()) out.notes = t.notes.trim().slice(0, 2000);
    const d = Number(t.durationMinutes);
    if (Number.isFinite(d)) out.durationMinutes = Math.min(720, Math.max(5, Math.round(d)));
    const p = String(t.priority || 'none').toLowerCase();
    out.priority = priorities.has(p) ? p : 'none';
    if (typeof t.start === 'string' && isoDateTime.test(t.start.slice(0, 19))) out.start = t.start.slice(0, 16);
    if (typeof t.deadline === 'string' && isoDateTime.test(t.deadline.slice(0, 19))) out.deadline = t.deadline.slice(0, 16);
    if (Array.isArray(t.subtasks)) out.subtasks = t.subtasks.filter((s) => typeof s === 'string' && s.trim()).slice(0, 20).map((s) => s.trim().slice(0, 200));
    return [out];
  });
  if (tasks.length === 0) throw new Error('Model response had no usable tasks');
  return { model, summary: typeof raw.summary === 'string' ? raw.summary.slice(0, 500) : 'Suggested plan', tasks };
}

/** Pulls the first JSON object out of model text (tolerates ```json fences). */
export function extractJson(text) {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  const candidate = fenced ? fenced[1] : text;
  const start = candidate.indexOf('{');
  const end = candidate.lastIndexOf('}');
  if (start < 0 || end <= start) throw new Error('No JSON object in model response');
  return JSON.parse(candidate.slice(start, end + 1));
}

function systemPrompt() {
  return [
    'You are a scheduling assistant inside a to-do app. Turn the user\'s goal, rough task list or availability into a realistic plan.',
    'Rules: break big goals into concrete tasks with subtasks; estimate durationMinutes (5-720); set priority high|medium|low|none;',
    'schedule start times (YYYY-MM-DDTHH:MM, local time) only inside the availability windows; never overlap the busy blocks or each other;',
    'leave short breaks; if something does not fit, omit "start" for it. Keep titles short and actionable.',
    'Reply with ONLY a JSON object: {"summary": string, "tasks": [{"title": string, "notes"?: string, "durationMinutes": number,',
    '"priority": string, "start"?: string, "deadline"?: string, "subtasks"?: string[]}]}',
  ].join(' ');
}

async function callAnthropic(cfg, req) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), cfg.timeoutMs);
  try {
    const res = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      signal: controller.signal,
      headers: { 'content-type': 'application/json', 'x-api-key': cfg.apiKey, 'anthropic-version': '2023-06-01' },
      body: JSON.stringify({
        model: cfg.model,
        max_tokens: 2000,
        system: systemPrompt(),
        messages: [{ role: 'user', content: JSON.stringify(req) }],
      }),
    });
    if (!res.ok) {
      const err = new Error(`Upstream error ${res.status}`);
      err.status = res.status === 429 ? 429 : 502;
      throw err;
    }
    const data = await res.json();
    const text = (data.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');
    return sanitizePlan(extractJson(text), data.model || cfg.model);
  } finally {
    clearTimeout(timer);
  }
}

/** Deterministic planner used when AI_PROVIDER=mock (local development and tests). */
export function mockPlan(req) {
  const lines = req.input.split(/\n|;|\.\s|,\s| and /i).map((s) => s.trim()).filter((s) => s.length > 2 && !/^i (have|am|'m)/i.test(s));
  const window = req.availability[0];
  let cursor = window ? new Date(window.start + ':00Z').getTime() : null;
  const end = window ? new Date(window.end + ':00Z').getTime() : null;
  const tasks = lines.slice(0, 8).map((title, i) => {
    const t = { title: title.replace(/^(help me|plan|please)\s+/i, '').replace(/^\w/, (c) => c.toUpperCase()), durationMinutes: 30, priority: i === 0 ? 'high' : 'medium', subtasks: [] };
    if (cursor != null && cursor + 30 * 60000 <= end) {
      t.start = new Date(cursor).toISOString().slice(0, 16);
      cursor += 35 * 60000;
    }
    return t;
  });
  return sanitizePlan({ summary: `Mock plan with ${tasks.length} tasks`, tasks: tasks.length ? tasks : [{ title: req.input.slice(0, 80) }] }, 'mock');
}

export function createServer(cfg = config()) {
  const hits = new Map();
  const limited = (ip) => {
    const now = Date.now();
    const arr = (hits.get(ip) || []).filter((t) => now - t < 60000);
    arr.push(now);
    hits.set(ip, arr);
    return arr.length > cfg.ratePerMinute;
  };
  const send = (res, status, obj) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(obj));
  };

  return http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/healthz') return send(res, 200, { ok: true, provider: cfg.provider });
    if (req.method !== 'POST' || req.url !== '/v1/plan') return send(res, 404, { error: 'Not found' });
    if (cfg.sharedToken && req.headers.authorization !== `Bearer ${cfg.sharedToken}`) return send(res, 401, { error: 'Unauthorized' });
    if (limited(req.socket.remoteAddress || 'unknown')) return send(res, 429, { error: 'Too many requests' });

    let size = 0;
    const chunks = [];
    for await (const chunk of req) {
      size += chunk.length;
      if (size > MAX_BODY) return send(res, 413, { error: 'Request too large' });
      chunks.push(chunk);
    }
    let body;
    try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return send(res, 400, { error: 'Invalid JSON' }); }
    const { value, error } = validateRequest(body);
    if (error) return send(res, 400, { error });

    try {
      if (cfg.provider === 'mock') return send(res, 200, mockPlan(value));
      if (cfg.provider !== 'anthropic' || !cfg.apiKey) return send(res, 503, { error: 'AI provider not configured' });
      return send(res, 200, await callAnthropic(cfg, value));
    } catch (e) {
      const status = e.name === 'AbortError' ? 504 : e.status || 502;
      // Never echo upstream details or secrets to the client.
      console.error(`[plan] ${status}: ${e.message}`);
      return send(res, status, { error: status === 504 ? 'AI service timed out' : 'AI service unavailable' });
    }
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  const cfg = config();
  createServer(cfg).listen(cfg.port, () => console.log(`Planner backend on :${cfg.port} (provider=${cfg.provider})`));
}
