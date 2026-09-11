# web

Static pages and the `/api` proxy. Port **3000**, and the only container that publishes one.

No framework, no build step, no dependencies: `server.js` is plain Node using only `node:http` and
`node:fs/promises`, and `package.json` lists no dependencies at all. Nothing here is compiled, bundled or
minified, so what is in `public/` is what the browser gets.

## Pages

| URL | What |
|---|---|
| `/` | Landing. The two public counts, no token needed. |
| `/login` | Log in, or create an account and drop straight in. |
| `/todos` | Your todos. Bounces to `/login` without a token. |
| `/account` | Change your name, email or password. |

```
public/index.html  login.html  todos.html  account.html
       app.js      token handling, the api() helper, the shared header
       style.css
server.js          the fixed URL map and the /api proxy
```

`server.js` serves pages from a **closed map** of URL to filename rather than resolving paths, so no request
can walk its way out of `public/`.

`app.js` is shared by all four pages so they cannot drift apart: it holds the token in `sessionStorage`, adds
the `Authorization` header to every call, renders the one header, and turns a `{"error": "..."}` body into a
thrown `Error` the pages display. A 401 while holding a token clears the session and bounces to
`/login?expired`; a 401 without one is just a failed login.

## The proxy

`/api/todos*` and `/api/public/todos*` go to todo-service, and the rest of `/api/*` to auth-service. Method,
path, headers and body are passed through untouched, so PATCH and the `Authorization` header need nothing
special.

The point is that the browser stays on one origin: neither service needs CORS configuration, and neither has
to publish a port. An upstream that is down answers 502 rather than hanging.

## Roles in the UI

There are none yet. A moderator or an admin sees exactly the pages a user does -- the extra reach they have
(`GET /api/accounts`, `PUT /api/accounts/{id}/role`, everyone's todos) is reachable over the API and has no
controls on any page. See the [root README](../../README.md#roles) for what the roles actually allow.

## Configuration

| Environment | Default |
|---|---|
| `PORT` | `3000` |
| `AUTH_URL` | `http://localhost:9081` |
| `TODO_URL` | `http://localhost:9082` |

## Running it alone

```bash
node apps/web/server.js     # with both services already up
```
