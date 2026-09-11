import { createServer, request } from 'node:http';
import { readFile } from 'node:fs/promises';

const PORT = process.env.PORT || 3000;
const AUTH = process.env.AUTH_URL || 'http://localhost:9081';
const TODO = process.env.TODO_URL || 'http://localhost:9082';

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
  if (req.url.startsWith('/api/todos')) return proxy(req, res, TODO);
  if (req.url.startsWith('/api/')) return proxy(req, res, AUTH);
  try {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(await readFile(new URL('./public/index.html', import.meta.url)));
  } catch {
    res.writeHead(404).end('not found');
  }
}).listen(PORT, () => console.log(`web  -> http://localhost:${PORT}\nauth -> ${AUTH}\ntodo -> ${TODO}`));
