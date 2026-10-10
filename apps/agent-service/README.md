# agent-service

User-owned agents, the MCP servers each may use, and the permission that is enforced on every tool call.
Port **9083**.

An agent is a name, instructions, a list of MCP servers each marked **READ** or **WRITE**, and one setting
for how far it may reach into its owner's *other* agents. No model runs here. Each agent **is an MCP
server**, at `POST /mcp?agent=<id>`: Claude Code, Cursor or any MCP client connects to it and is offered
exactly the tools those permissions allow; every tool it calls is checked again, against the row as it is in
the database at that moment, before anything is proxied on. What it did -- and what it was refused -- is
written to the agent's activity log, which the page shows.

Like todo-service it verifies tokens in process against auth-service's JWKS and never calls auth-service per
request -- except to mint an agent token, once, when the owner asks. It is the only service that writes to
the `agent` database.

## Packages

```
agent/     Agent  AgentMcpServer  Access  OthersAccess  AgentRepository  AgentService  AgentController
           NewAgent  UpdateAgent  NewMcpServer  UpdateMcpServer  AgentResponse  McpServerResponse  ServerUrls
activity/  Activity  ActivityRepository  ActivityLog  ActivityResponse
mcp/       AgentMcpServer (the /mcp endpoint and its handler)  McpTools  AgentTools  AccessDenied  ToolResult
token/     AgentTokens   (JwtVerifier, Revocations and Caller come from the shared apps/token module)
stats/     StatsController  PublicStats
support/   AgentExceptionAdvice
```

`mcp` depends on `agent` and `activity`; `agent` depends on `activity` and `token`; nothing points back.
`com.demo.token` is the [`apps/token`](../token/README.md) module todo-service and account-service share.
`AgentService` is the one place that decides whose agents a caller sees, and both the REST API and the tools
one agent uses on another go through it.

## Who owns what

Agents belong to the account id in the token's `sub`, and **only** to it: there is no role that sees
everybody's agents, an admin included. Another account's agent id comes back **404, not 403**, from every
endpoint, from every agent tool, and from `/mcp`, so neither answer says whether it exists.

An agent acts **as its owner**. An MCP client connects with either the owner's own login token or an **agent
token**: minted by auth-service on `POST /api/agents/{id}/token`, with the owner as `sub`, role `AGENT`, the
agent's id in an `agent` claim, and a 30-day life. `/mcp` accepts a token when its subject owns the agent
and, for an agent token, the claim names that very agent -- which also lets an agent token connect to plain
`/mcp` with no `agent=` parameter; a login token must say which agent it means. **Revoke tokens** on the
page (`POST /api/agents/{id}/token/revoke`) kills every token made for that one agent: auth-service bumps
the agent's own version, the token's `agentVer` falls behind it, and every service refuses it within its
ten-second poll of the revocation feed. The owner's login and their other agents are untouched. Whatever token the client connected with is what a
server row marked *forward caller token* receives as its `Authorization` header -- which is how the built-in
todo server knows whose todos to show, and why a READ agent cannot see anybody else's. Agents are not
accounts; the `AGENT` role lives only on these tokens, and **every `/api` endpoint in every service answers it
403**. Here, an agent token that could edit agents would widen its own access or mint itself fresh tokens,
the very thing the agent tools refuse; at todo-service and account-service it would skip the READ/WRITE gate
below and the activity log; at auth-service it could change its owner's password. Its one way in is `/mcp`,
and the only places it is good downstream are the `/mcp` endpoints agent-service forwards it to.

## The permission rule

Two levels, one rule, enforced in `McpTools`:

| | offered on `tools/list` | allowed on `tools/call` |
|---|---|---|
| **READ** | only tools whose MCP annotation says `readOnlyHint: true` | the same |
| **WRITE** | every tool the server lists | every tool |

A tool with no annotation is taken to **write**. That is the server's own declaration being trusted, so the
rule is only as good as the server: the built-in todo server annotates `list_todos` and nothing else.

Two checks on purpose. `tools/list` is filtered by the access each row has, so a READ server's writing tools
are not even described to the connecting agent. Each `tools/call` then re-reads the row: an owner who flips a
server to READ, or removes it, while an agent is connected is obeyed from its next call, and an agent that
calls a tool it was never offered is refused all the same. A refusal becomes an `isError` tool result the
agent can read and a `tool_denied` line in the log.

A result that is allowed is passed through **whole**: the content list -- text, images, audio, embedded
resources, resource links -- and any `structuredContent` reach the connecting agent exactly as the server
produced them, MIME types and bytes included. Only the activity log flattens it, to the text parts plus a
`[image image/png]`-style marker for anything else.

Tool names reach the client as `<server>__<tool>`, so two servers with the same tool cannot collide; server
names are therefore short, lower-case and unique per agent.

## Where a server may point

agent-service POSTs to the servers an owner attaches, from inside the compose network -- where the database
and auth-service's `/internal` endpoints live. `ServerUrls` is the rule that keeps an owner's URL from
pointing it there:

- **The deployment's own servers pass by name**: `agents.trusted-server-urls`, plus the built-in todo
  server, compared exactly as written (a different port or path on the same host is not the trusted server).
  They are private addresses by design.
- **Any other URL** must be `http(s)`, name a host with a dot in it -- a bare name is a compose service, or
  `localhost` -- and resolve **only** to public addresses: no loopback, any-local, private (10/8, 172.16/12,
  192.168/16), link-local (169.254/16, where the cloud metadata address 169.254.169.254 lives, and fe80::/10),
  carrier-grade NAT (100.64/10), unique-local (fc00::/7) or multicast address. An IPv4-mapped IPv6 literal is
  judged as the IPv4 address it wraps; a name that does not resolve is refused, not deferred.
- **Checked twice.** On save, as a 400 with the reason, so nothing private is ever stored; and again right
  before every connection, against what the name resolves to at that moment, so a name that was public when
  saved and points somewhere private since (DNS rebinding) is refused on `tools/list` (the server is skipped
  and logged as `could not connect: refused: …`) and on `tools/call` (an error result). Redirects are never
  followed, so a public server cannot answer 302 to a private address either.

## Other agents

`othersAccess` on each agent gates seven built-in tools (`AgentTools`), scoped to the **same owner**:

| needs | tools |
|---|---|
| READ or WRITE | `list_agents` · `get_agent` · `get_agent_activity` |
| WRITE | `update_agent` · `add_mcp_server` · `set_mcp_access` · `remove_mcp_server` |

It is re-read from the database before every call like the server access is. An agent may **never change
its own configuration**, whatever its level: with that door open, one `set_mcp_access` call would be all the
escalation it took. Changes an agent makes are logged on the agent it changed, prefixed
`by agent '<name>' (#<id>): `, and the call itself on the agent that made it.

## Activity

Plain text lines, newest first, a hundred at a time, deleted with the agent:

| kind | when |
|---|---|
| `connected` | an MCP client sent `initialize`: its name and version |
| `tool_call` | a tool ran: `todos__list_todos {} -> ok: …` or `-> error: …`, text as it is and `[image image/png]`, `[structured {…}]` for the rest; also a server that could not be reached |
| `tool_denied` | a tool was refused and why: `todos__add_todo: needs WRITE on server 'todos' (has READ)` |
| `config_changed` | anything edited, by the owner or by another agent; also `agent token issued` and `agent tokens revoked` |

## The MCP endpoint

`POST /mcp?agent=<id>`, MCP Streamable HTTP, **stateless**: no session id, no event stream, nothing kept
between requests. Every request carries the token and the agent id and re-reads the agent, which is what
makes a change on the page bite on the very next call. The web proxy forwards `/mcp` as-is, so from outside
the URL is `http://localhost:3000/mcp?agent=<id>`.

```bash
claude mcp add --transport http todos "http://localhost:3000/mcp?agent=<id>" --header "Authorization: Bearer <token>"
```

The handler (`AgentMcpServer.Handler`) is written out rather than built from the SDK's static tool list,
because the tools differ per agent and per request. It answers `initialize` (the agent's instructions go in
the result's `instructions`, so the connecting agent reads them first), `ping`, `tools/list` and
`tools/call`; anything else is JSON-RPC *method not found*. A bad token, a missing `agent` parameter or an
agent that is not the caller's come back as JSON-RPC errors, which is what the client can show its user.

The query parameter rather than a path segment: the SDK's servlet transport matches the request URI against
one fixed endpoint.

## Endpoints

| Method | Path | Body / notes |
|---|---|---|
| GET | `/api/agents` | the caller's agents, with their servers |
| POST | `/api/agents` | `{name, instructions?, othersAccess?}` -- starts with the built-in `todos` server as READ |
| GET | `/api/agents/{id}` | |
| PATCH | `/api/agents/{id}` | `{name?, instructions?, othersAccess?}` -- only the fields sent change |
| DELETE | `/api/agents/{id}` | and its activity |
| POST | `/api/agents/{id}/servers` | `{name, url, access, forwardCallerToken?, authHeader?}` |
| PATCH | `/api/agents/{id}/servers/{sid}` | `{name?, url?, access?, forwardCallerToken?, authHeader?}`; an empty `authHeader` clears it |
| DELETE | `/api/agents/{id}/servers/{sid}` | |
| GET | `/api/agents/{id}/activity` | newest first, at most 100 |
| POST | `/api/agents/{id}/token` | → `{token}`: a 30-day agent token, shown once, never stored; 502 if auth-service is down |
| POST | `/api/agents/{id}/token/revoke` | kills every token made for this agent; the owner's login and other agents live on; 502 if auth-service is down |
| POST | `/mcp?agent={id}` | MCP Streamable HTTP, see above |
| GET | `/api/public/agents/stats` | `{agents}` -- no token |

A server's stored `authHeader` is never returned; responses carry `hasAuthHeader` instead. Rejections come
back as `{"error": "..."}` like everywhere else.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | `9083` |
| `auth.jwks-uri` | `AUTH_JWKS_URI` | `http://localhost:9081/api/jwks.json` |
| `auth.token-versions-uri` | `AUTH_TOKEN_VERSIONS_URI` | `http://localhost:9081/internal/token-versions` |
| `auth.agent-tokens-uri` | `AUTH_AGENT_TOKENS_URI` | `http://localhost:9081/internal/agent-tokens`; `/{id}/revoke` under it is where a per-agent revoke goes |
| `auth.internal-secret` | `AUTH_INTERNAL_SECRET` | `dev-internal-secret` -- sent as `X-Internal-Secret` when asking for a token; compose requires `INTERNAL_SECRET` in `.env` |
| `spring.datasource.*` | `SPRING_DATASOURCE_*` | `jdbc:postgresql://localhost:5432/agent`, `agent` / `agent` |
| `agents.todo-mcp-url` | `AGENTS_TODO_MCP_URL` | `http://localhost:9082/mcp`; blank seeds no server; always trusted |
| `agents.trusted-server-urls` | `AGENTS_TRUSTED_SERVER_URLS` | `http://localhost:9084/mcp`; comma separated, the deployment's other servers an owner may attach by their private name |

## Limitations, on purpose

- **The connect-time URL check resolves the name, then the HTTP client resolves it again.** The JVM caches a
  positive lookup for 30 seconds, so the two agree in practice; pinning the connection to the checked
  address needs a custom resolver on the client. The connecting token is forwarded only to servers
  explicitly marked for it.
- **Every `tools/call` reconnects** to the one downstream server: initialize, list its tools (for the
  read-only annotation), call, close. Three round trips per call. A short per-URL cache is the upgrade if
  latency ever matters.
- **A per-agent revoke bites within ten seconds, not at once.** The other services poll the revocation feed
  rather than ask auth-service per request, so a revoked agent token keeps working for up to one poll window.
- **`authHeader` is stored in plain text**, like the database credentials in this demo.
- **The forwarded token is the owner's full authority.** The built-in todo server checks it like the REST API
  does, so agent-service's READ/WRITE gate is the only thing between a READ agent and `delete_todo`. That is
  the feature; it is also why the gate is checked on every call and never left to the connecting agent.

## Running it alone

```bash
docker compose up -d db
./mvnw -pl apps/agent-service spring-boot:run   # with auth-service and todo-service up
./mvnw -pl apps/agent-service test              # SQLite backed: needs nothing running
```

Tests: `AgentMcpServerTests` runs the permission rules end to end -- the real MCP client connects to `/mcp`
as an external agent would, against a real MCP server mounted in the same context -- including a permission
flipped between two calls, an agent trying to widen its own access, and who may connect at all;
including a server trusted when saved and refused at connect, and an image with structured content coming
through unchanged; `AgentServiceTests` the ownership rules, the
activity log and a private URL refused on add and on edit; `ServerUrlsTests` every refused address category,
exact trust, and a name that moves to a private address between save and connect, all against a resolver
table rather than DNS; `ApiContractTests` the 401, 404 and token shapes. The token check itself is tested
once, in `apps/token`.
