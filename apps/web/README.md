# web

Static pages and the `/api` proxy. Port **3000**, and the only container that publishes one.

No framework, no build step, no dependencies: `server.js` is plain Node using only `node:http` and
`node:fs/promises`, and `package.json` lists no dependencies at all. Nothing here is compiled, bundled or
minified, so what is in `public/` is what the browser gets.

## Pages

| URL | What |
|---|---|
| `/` | Landing. The two public counts, no token needed. |
| `/login` | Log in, create an account, or continue with a configured provider. |
| `/todos` | Your todos. Bounces to `/login` without a token. |
| `/account` | Change your name, email or password. |
| `/agents` | Your agents: create one, delete one, see each one's servers and access at a glance. |
| `/agent?id=` | One agent: its prompt, its MCP servers and their READ/WRITE, access to your other agents, a Run box, and its activity. |
| `/admin` | Admin only: accounts, suspend and revoke. |

```
public/index.html  login.html  todos.html  account.html  agents.html  agent.html  admin.html
       app.js      token handling, the api() helper, the shared header
       style.css
server.js          the fixed URL map and the /api proxy
```

`server.js` serves pages from a **closed map** of URL to filename rather than resolving paths, so no request
can walk its way out of `public/`.

`app.js` is shared by every page so they cannot drift apart: it holds the token in `sessionStorage`, adds
the `Authorization` header to every call, renders the one header, and turns a `{"error": "..."}` body into a
thrown `Error` the pages display. A 401 while holding a token clears the session and bounces to
`/login?expired`; a 401 without one is just a failed login.

## Signing in with a provider

`/login` asks auth-service for `GET /api/oauth/providers` and draws one button per provider the deployment
configured -- none configured, no section. The button is a plain link to
`/api/oauth/<provider>/start`, because the browser itself has to travel through the redirects; a `fetch`
could not follow them to another origin.

The browser comes back to `/login#token=...&name=...`, and the page stores that the same way a password
login does. A **fragment** rather than a query string, so the token never reaches this server, its access
log, or a `Referer` header. The fragment is cleared with `history.replaceState` either way, so a refresh
cannot replay a spent sign-in; a failure arrives as `#error=...` and renders in the same place a wrong
password does.

## The proxy

`/api/agents*` and `/api/public/agents*` go to agent-service, `/api/todos*` and `/api/public/todos*` to
todo-service, and the rest of `/api/*` to auth-service. Method,
path, headers and body are passed through untouched, so PATCH, the `Authorization` header, the OAuth state
cookie and the 302s of the provider flow all need nothing special.

The point is that the browser stays on one origin: no service needs CORS configuration, and none has to
publish a port. An upstream that is down answers 502 rather than hanging. A run (`POST /api/agents/{id}/run`)
can take a while; nothing here times it out, and the page disables its Run button until the reply lands.

## Agents in the UI

`/agents` and `/agent` talk to agent-service only. The detail page edits everything in place -- a server's
access `<select>` saves on change, so flipping READ to WRITE while a run is going is a one-click way to watch
the next tool call obey it -- and refreshes the activity table after every save and run. A stored
authorization header shows only as *header set*; the value is never sent back.

## Roles in the UI

An admin gets an **Admin** link in the header and the `/admin` page: every account with its role and status,
and per account **Suspend** / **Reactivate** and **Revoke access** (sign out everywhere). The link and the
page read the role out of the token only to decide what to draw; every action is refused server-side for
anyone but an admin. Role changes (`PUT /api/accounts/{id}/role`) and a moderator's wider reads are still
API-only. See the [root README](../../README.md#roles) for what the roles actually allow.

## Configuration

| Environment | Default |
|---|---|
| `PORT` | `3000` |
| `AUTH_URL` | `http://localhost:9081` |
| `TODO_URL` | `http://localhost:9082` |
| `AGENT_URL` | `http://localhost:9083` |

## Running it alone

```bash
node apps/web/server.js     # with the three services already up
```

## Tests

`auth.test.js` covers the authentication paths only: the proxy leg to auth-service (method, body,
`Authorization` header and the provider 302 pass through; a dead upstream answers a JSON 502), the routing
of `/api/agents*` to agent-service, and the 401 rules in `app.js`. Plain `node --test`, no dependencies.

```bash
npm test --prefix apps/web
```
