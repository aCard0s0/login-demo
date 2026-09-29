// Authentication paths only: the proxy leg to auth-service and the token handling in app.js.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

// A stand-in auth-service that records what the proxy delivered.
const seen = [];
const auth = createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    seen.push({ method: req.method, url: req.url, headers: req.headers, body });
    if (req.url.startsWith('/api/oauth/')) return res.writeHead(302, { location: 'https://provider/authorize' }).end();
    if (req.url === '/api/login') return res.writeHead(401, { 'content-type': 'application/json' }).end('{"error":"invalid credentials"}');
    res.writeHead(200, { 'content-type': 'application/json' }).end('{"ok":true}');
  });
});

let web, base;
before(async () => {
  auth.listen(0);
  await once(auth, 'listening');
  const free = createServer().listen(0);
  await once(free, 'listening');
  const port = free.address().port;
  free.close();
  web = spawn(process.execPath, ['server.js'], {
    cwd: import.meta.dirname,
    env: { ...process.env, PORT: port, AUTH_URL: `http://localhost:${auth.address().port}` },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  await once(web.stdout, 'data');
  base = `http://localhost:${port}`;
});
after(() => { web.kill(); auth.close(); });

test('POST /api/login reaches auth-service with method, body and status intact', async () => {
  const res = await fetch(`${base}/api/login`, { method: 'POST', body: '{"email":"a@b.c","password":"x"}' });
  assert.equal(res.status, 401);
  assert.deepEqual(await res.json(), { error: 'invalid credentials' });
  assert.equal(seen.at(-1).method, 'POST');
  assert.equal(seen.at(-1).body, '{"email":"a@b.c","password":"x"}');
});

test('Authorization header passes through untouched', async () => {
  await fetch(`${base}/api/accounts/me`, { headers: { authorization: 'Bearer t0k' } });
  assert.equal(seen.at(-1).url, '/api/accounts/me');
  assert.equal(seen.at(-1).headers.authorization, 'Bearer t0k');
});

test('provider start returns the 302 for the browser to follow, not the proxy', async () => {
  const res = await fetch(`${base}/api/oauth/github/start`, { redirect: 'manual' });
  assert.equal(res.status, 302);
  assert.equal(res.headers.get('location'), 'https://provider/authorize');
});

test('auth-service down answers a JSON 502 instead of hanging', async () => {
  const free = createServer().listen(0);
  await once(free, 'listening');
  const port = free.address().port;
  free.close();
  const dead = spawn(process.execPath, ['server.js'], {
    cwd: import.meta.dirname,
    env: { ...process.env, PORT: port, AUTH_URL: 'http://localhost:1' },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  await once(dead.stdout, 'data');
  try {
    const res = await fetch(`http://localhost:${port}/api/login`, { method: 'POST', body: '{}' });
    assert.equal(res.status, 502);
    assert.deepEqual(await res.json(), { error: 'upstream unavailable' });
  } finally {
    dead.kill();
  }
});

// app.js in the browser: stub the three globals it touches, then drive api() through the 401 rules.
test('api(): token rides as Bearer; 401 with a token clears the session and bounces; 401 without one is a login failure', async () => {
  const store = new Map();
  globalThis.sessionStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), clear: () => store.clear() };
  globalThis.location = { href: '/todos', pathname: '/todos' };
  globalThis.document = { querySelector: () => null, getElementById: () => null };
  let sent;
  globalThis.fetch = async (path, init) => {
    sent = { path, init };
    return new Response('{"error":"invalid credentials"}', { status: 401, headers: { 'content-type': 'application/json' } });
  };
  const { api, setSession } = await import('./public/app.js');

  await assert.rejects(api('/api/login', { method: 'POST' }), { message: 'invalid credentials' });
  assert.equal(sent.init.headers.authorization, undefined);
  assert.equal(location.href, '/todos', 'no token held, so no bounce');

  setSession('t0k', 'Ana');
  await assert.rejects(api('/api/accounts/me'), { message: 'session expired' });
  assert.equal(sent.init.headers.authorization, 'Bearer t0k');
  assert.equal(store.size, 0, 'session forgotten');
  assert.equal(location.href, '/login?expired');
});
