import { createServer, request } from 'node:http';
import { readFile } from 'node:fs/promises';

const PORT = process.env.PORT || 3000;
const AUTH = process.env.AUTH_URL || 'http://localhost:9081';
const TODO = process.env.TODO_URL || 'http://localhost:9082';

// Clean URL -> file under public/. A closed set, so no request can walk its way out of the folder.
const PAGES = {
  '/': 'index.html',
  '/login': 'login.html',
  '/todos': 'todos.html',
  '/account': 'account.html',
};
const ASSETS = {
  '/app.js': ['app.js', 'text/javascript'],
  '/style.css': ['style.css', 'text/css'],
};

// Proxying /api keeps the browser on one origin, so no CORS config in either service.
const proxy = (req, res, target) => {
  const { hostname, port } = new URL(target);
  const upstream = request(
    { hostname, port, path: req.url, method: req.method, headers: { ...req.headers, host: `${hostname}:${port}` } },
    (up) => {
      res.writeHead(up.statusCode, up.headers);
      up.pipe(res);
    },
  );
  upstream.on('error', () => res.writeHead(502).end('upstream unavailable'));
  req.pipe(upstream);
};

createServer(async (req, res) => {
  const path = req.url.split('?')[0];
  // Both the private todos and the public todo count live in todo-service; everything else is auth-service.
  if (path.startsWith('/api/todos') || path.startsWith('/api/public/todos')) return proxy(req, res, TODO);
  if (path.startsWith('/api/')) return proxy(req, res, AUTH);

  const [file, type] = ASSETS[path] ?? [PAGES[path], 'text/html'];
  if (!file) return res.writeHead(404).end('not found');
  try {
    res.writeHead(200, { 'content-type': `${type}; charset=utf-8` });
    res.end(await readFile(new URL(`./public/${file}`, import.meta.url)));
  } catch {
    res.writeHead(404).end('not found');
  }
}).listen(PORT, () => console.log(`web  -> http://localhost:${PORT}\nauth -> ${AUTH}\ntodo -> ${TODO}`));
