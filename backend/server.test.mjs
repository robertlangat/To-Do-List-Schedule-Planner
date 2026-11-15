import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer, extractJson, sanitizePlan, validateRequest } from './server.mjs';

const good = {
  input: 'I have two hours after work. Help me revise for an exam and plan tomorrow\'s errands.',
  date: '2026-11-16',
  timezone: 'Africa/Nairobi',
  availability: [{ start: '2026-11-16T17:30', end: '2026-11-16T19:30' }],
  busy: [{ start: '2026-11-16T18:00', end: '2026-11-16T18:30', title: 'Busy' }],
};

async function withServer(cfg, fn) {
  const server = createServer({ port: 0, provider: 'mock', apiKey: '', model: 'm', sharedToken: '', ratePerMinute: 100, timeoutMs: 1000, ...cfg });
  await new Promise((r) => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try { await fn(base); } finally { server.close(); }
}

test('validateRequest accepts the app payload and rejects bad input', () => {
  assert.ok(validateRequest(good).value);
  assert.match(validateRequest({ ...good, input: '' }).error, /input/);
  assert.match(validateRequest({ ...good, date: '16/11/2026' }).error, /date/);
  assert.match(validateRequest({ ...good, input: 'x'.repeat(5000) }).error, /4000/);
  assert.match(validateRequest({ ...good, busy: [{ start: 'nope' }] }).error, /busy/);
});

test('sanitizePlan clamps and drops bad items', () => {
  const p = sanitizePlan({ tasks: [{ title: ' A ', durationMinutes: 9999, priority: 'URGENT' }, { title: '' }, 'x'] }, 'm');
  assert.equal(p.tasks.length, 1);
  assert.equal(p.tasks[0].title, 'A');
  assert.equal(p.tasks[0].durationMinutes, 720);
  assert.equal(p.tasks[0].priority, 'none');
  assert.throws(() => sanitizePlan({ tasks: [] }, 'm'));
});

test('extractJson tolerates code fences and prose', () => {
  assert.deepEqual(extractJson('Here you go:\n```json\n{"tasks":[]}\n```'), { tasks: [] });
  assert.deepEqual(extractJson('{"a":1}'), { a: 1 });
  assert.throws(() => extractJson('no json'));
});

test('POST /v1/plan returns a structured plan (mock provider)', async () => {
  await withServer({}, async (base) => {
    const res = await fetch(`${base}/v1/plan`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(good) });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.model, 'mock');
    assert.ok(body.tasks.length >= 2);
    assert.ok(body.tasks.every((t) => typeof t.title === 'string' && t.title.length > 0));
  });
});

test('shared token, bad JSON, not configured and rate limit are handled', async () => {
  await withServer({ sharedToken: 'secret' }, async (base) => {
    const r = await fetch(`${base}/v1/plan`, { method: 'POST', body: JSON.stringify(good) });
    assert.equal(r.status, 401);
    const ok = await fetch(`${base}/v1/plan`, { method: 'POST', headers: { authorization: 'Bearer secret' }, body: JSON.stringify(good) });
    assert.equal(ok.status, 200);
  });
  await withServer({}, async (base) => {
    assert.equal((await fetch(`${base}/v1/plan`, { method: 'POST', body: '{nope' })).status, 400);
  });
  await withServer({ provider: 'anthropic', apiKey: '' }, async (base) => {
    assert.equal((await fetch(`${base}/v1/plan`, { method: 'POST', body: JSON.stringify(good) })).status, 503);
  });
  await withServer({ ratePerMinute: 1 }, async (base) => {
    await fetch(`${base}/v1/plan`, { method: 'POST', body: JSON.stringify(good) });
    assert.equal((await fetch(`${base}/v1/plan`, { method: 'POST', body: JSON.stringify(good) })).status, 429);
  });
});
