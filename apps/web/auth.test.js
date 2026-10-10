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

// A stand-in agent-service, so a request meant for it can be seen arriving there and not at auth-service.
const seenByAgent = [];
const agent = createServer((req, res) => {
  seenByAgent.push({ method: req.method, url: req.url });
  res.writeHead(200, { 'content-type': 'application/json' }).end('[]');
});

// And a stand-in wallet-service.
const seenByWallet = [];
const wallet = createServer((req, res) => {
  seenByWallet.push({ method: req.method, url: req.url });
  res.writeHead(200, { 'content-type': 'application/json' }).end('[]');
});

let web, base;
before(async () => {
  auth.listen(0);
  await once(auth, 'listening');
  agent.listen(0);
  await once(agent, 'listening');
  wallet.listen(0);
  await once(wallet, 'listening');
  const free = createServer().listen(0);
  await once(free, 'listening');
  const port = free.address().port;
  free.close();
  web = spawn(process.execPath, ['server.js'], {
    cwd: import.meta.dirname,
    env: { ...process.env, PORT: port, AUTH_URL: `http://localhost:${auth.address().port}`, AGENT_URL: `http://localhost:${agent.address().port}`, WALLET_URL: `http://localhost:${wallet.address().port}` },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  await once(web.stdout, 'data');
  base = `http://localhost:${port}`;
});
after(() => { web.kill(); auth.close(); agent.close(); wallet.close(); });

test('POST /api/login reaches auth-service with method, body and status intact', async () => {
  const res = await fetch(`${base}/api/login`, { method: 'POST', body: '{"email":"a@b.c","password":"x"}' });
  assert.equal(res.status, 401);
  assert.deepEqual(await res.json(), { error: 'invalid credentials' });
  assert.equal(seen.at(-1).method, 'POST');
  assert.equal(seen.at(-1).body, '{"email":"a@b.c","password":"x"}');
});

test('Authorization header passes through untouched', async () => {
  await fetch(`${base}/api/users/me`, { headers: { authorization: 'Bearer t0k' } });
  assert.equal(seen.at(-1).url, '/api/users/me');
  assert.equal(seen.at(-1).headers.authorization, 'Bearer t0k');
});

test('/api/agents and /api/public/agents reach agent-service, not auth-service', async () => {
  const before = seen.length;
  const res = await fetch(`${base}/api/agents/7/activity?x=1`, { headers: { authorization: 'Bearer t0k' } });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), []);
  assert.equal(seenByAgent.at(-1).url, '/api/agents/7/activity?x=1');
  await fetch(`${base}/api/public/agents/stats`);
  assert.equal(seenByAgent.at(-1).url, '/api/public/agents/stats');
  // The MCP endpoint an external agent connects to: the query string names the agent and must survive.
  await fetch(`${base}/mcp?agent=7`, { method: 'POST', body: '{}' });
  assert.equal(seenByAgent.at(-1).method, 'POST');
  assert.equal(seenByAgent.at(-1).url, '/mcp?agent=7');
  assert.equal(seen.length, before, 'auth-service saw none of it');
});

test('/api/wallets and /api/public/wallets reach wallet-service; /api/users still reaches auth-service', async () => {
  const before = seen.length;
  await fetch(`${base}/api/wallets/3/transfers`, { headers: { authorization: 'Bearer t0k' } });
  assert.equal(seenByWallet.at(-1).url, '/api/wallets/3/transfers');
  await fetch(`${base}/api/public/wallets/stats`);
  assert.equal(seenByWallet.at(-1).url, '/api/public/wallets/stats');
  assert.equal(seen.length, before, 'auth-service saw none of it');
  await fetch(`${base}/api/users/me`);
  assert.equal(seen.at(-1).url, '/api/users/me');
  // A whole segment, not a prefix: /api/wallets-archive is not wallet-service's.
  await fetch(`${base}/api/wallets-archive`);
  assert.equal(seen.at(-1).url, '/api/wallets-archive');
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
  await assert.rejects(api('/api/users/me'), { message: 'session expired' });
  assert.equal(sent.init.headers.authorization, 'Bearer t0k');
  assert.equal(store.size, 0, 'session forgotten');
  assert.equal(location.href, '/login?expired');
});
