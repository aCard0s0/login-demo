import { createServer, request } from 'node:http';
import { readFile } from 'node:fs/promises';

const PORT = process.env.PORT || 3000;
const AUTH = process.env.AUTH_URL || 'http://localhost:9081';
const TODO = process.env.TODO_URL || 'http://localhost:9082';
const AGENT = process.env.AGENT_URL || 'http://localhost:9083';
const WALLET = process.env.WALLET_URL || 'http://localhost:9084';

// Clean URL -> file under public/. A closed set, so no request can walk its way out of the folder.
const PAGES = {
  '/': 'index.html',
  '/login': 'login.html',
  '/todos': 'todos.html',
  '/profile': 'profile.html',
  '/admin': 'admin.html',
  '/agents': 'agents.html',
  '/agent': 'agent.html',
  '/wallets': 'wallets.html',
};
const ASSETS = {
  '/app.js': ['app.js', 'text/javascript'],
  '/style.css': ['style.css', 'text/css'],
};

// Proxying /api keeps the browser on one origin, so no CORS config in either service.
const proxy = (req, res, target, path) => {
  const { hostname, port } = new URL(target);
  const upstream = request(
    { hostname, port, path, method: req.method, headers: { ...req.headers, host: `${hostname}:${port}` } },
    (up) => {
      res.writeHead(up.statusCode, up.headers);
      up.pipe(res);
    },
  );
  upstream.on('error', () => {
    // Headers already streamed means the upstream died mid-response; cutting the socket is all that is left.
    if (res.headersSent) return res.destroy();
    res.writeHead(502, { 'content-type': 'application/json' }).end(JSON.stringify({ error: 'upstream unavailable' }));
  });
  req.pipe(upstream);
};

createServer(async (req, res) => {
  // Routed and forwarded as the URL parser resolves it, dot segments (encoded ones too) already collapsed, so
  // /api/../internal cannot ride through as /api and be resolved upstream to a path never meant to be public.
  const { pathname: path, search } = new URL(req.url, 'http://web');
  // Agents, their public count and the MCP endpoint an external agent connects to live in agent-service, the
  // wallets and their public count in wallet-service, the private todos and the public todo count in
  // todo-service; everything else is auth-service.
  // Whole path segments, so /api/wallets-archive is not /api/wallets.
  const under = (prefix) => path === prefix || path.startsWith(prefix + '/');
  if (path === '/mcp' || under('/api/agents') || under('/api/public/agents')) return proxy(req, res, AGENT, path + search);
  if (under('/api/wallets') || under('/api/public/wallets')) return proxy(req, res, WALLET, path + search);
  if (under('/api/todos') || under('/api/public/todos')) return proxy(req, res, TODO, path + search);
  if (path.startsWith('/api/')) return proxy(req, res, AUTH, path + search);

  const [file, type] = ASSETS[path] ?? [PAGES[path], 'text/html'];
  if (!file) return res.writeHead(404).end('not found');
  try {
    res.writeHead(200, { 'content-type': `${type}; charset=utf-8` });
    res.end(await readFile(new URL(`./public/${file}`, import.meta.url)));
  } catch {
    res.writeHead(404).end('not found');
  }
}).listen(PORT, () => console.log(`web     -> http://localhost:${PORT}\nauth    -> ${AUTH}\ntodo    -> ${TODO}\nagent   -> ${AGENT}\nwallet  -> ${WALLET}`));
