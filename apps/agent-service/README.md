# agent-service

User-owned agents, the MCP servers each may use, and the permission that is enforced on every tool call.
Port **9083**.

An agent is a name, a system prompt, a list of MCP servers each marked **READ** or **WRITE**, and one setting
for how far it may reach into its owner's *other* agents. Running one asks Claude, through the Anthropic Java
SDK, with exactly the tools those permissions allow; every tool the model calls is checked again, against the
row as it is in the database at that moment, before anything runs. What it did -- and what it was refused --
is written to the agent's activity log, which the page shows.

Like todo-service it verifies tokens in process against auth-service's JWKS and never calls auth-service per
request. It is the only service that writes to the `agent` database.

## Packages

```
agent/     Agent  AgentMcpServer  Access  OthersAccess  AgentRepository  AgentService  AgentController
           NewAgent  UpdateAgent  NewMcpServer  UpdateMcpServer  AgentResponse  McpServerResponse
activity/  Activity  ActivityRepository  ActivityLog  ActivityResponse
run/       AgentRunner  McpTools  AgentTools  Model  AnthropicModel  AccessDenied  ToolResult
           RunController  RunRequest  RunResponse
token/     JwtVerifier  Revocations  Caller          (the same three files as todo-service)
stats/     StatsController  PublicStats
support/   AgentExceptionAdvice
```

`run` depends on `agent` and `activity`; `agent` depends on `activity` and `token`; nothing points back.
`AgentService` is the one place that decides whose agents a caller sees, and both the REST API and the tools
one agent uses on another go through it.

## Who owns what

Agents belong to the account id in the token's `sub`, and **only** to it: there is no role that sees
everybody's agents, an admin included. Another account's agent id comes back **404, not 403**, from every
endpoint and from every agent tool, so neither answer says whether it exists.

An agent runs **as its owner**. The run is started from the browser with the owner's token, and a server row
marked *forward caller token* receives that very token as its `Authorization` header -- which is how the
built-in todo server knows whose todos to show, and why a READ agent cannot see anybody else's. Agents are
not accounts; the `AGENT` role in auth-service stays reserved.

## The permission rule

Two levels, one rule, enforced in `McpTools`:

| | offered to the model | allowed when called |
|---|---|---|
| **READ** | only tools whose MCP annotation says `readOnlyHint: true` | the same |
| **WRITE** | every tool the server lists | every tool |

A tool with no annotation is taken to **write**. That is the server's own declaration being trusted, so the
rule is only as good as the server: the built-in todo server annotates `list_todos` and nothing else.

Two checks on purpose. The tools *offered* are filtered by the access the row had when the run started, so a
READ server's writing tools are not even described to the model. Each *call* then re-reads the row: an owner
who flips a server to READ, or removes it, while a run is going is obeyed from the next call, and a model
that calls a tool it was never offered is refused all the same. A refusal becomes an `is_error` tool result
the model can read and a `tool_denied` line in the log.

Tool names reach the model as `<server>__<tool>`, so two servers with the same tool cannot collide; server
names are therefore short, lower-case and unique per agent.

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
| `run_started` | a run begins; the prompt's first 200 characters |
| `tool_call` | a tool ran: `todos__list_todos {} -> ok: …` or `-> error: …`; also a server that could not be reached |
| `tool_denied` | a tool was refused and why: `todos__add_todo: needs WRITE on server 'todos' (has READ)` |
| `run_finished` | how many turns and the reply's first 200 characters, or `failed: …` |
| `config_changed` | anything edited, by the owner or by another agent |

## A run

`POST /api/agents/{id}/run` with `{prompt}`, synchronous: the browser waits. `AgentRunner` connects to each
server (a dead one is logged and skipped), builds the tool list, and loops: ask the model, run or refuse each
tool it called, send every result back in one message, until the model stops or `agents.max-turns` (8) is
reached. Anything but a tool call -- `end_turn`, `max_tokens`, a `refusal` -- ends the run. Worst case is
turns × (60s model + 30s per tool); a few seconds is typical.

`Model` is a one-method interface so the tests can script the model's answers; `AnthropicModel` is the real
one, built on first use so a deployment with no key still starts and only Run answers 503.

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
| POST | `/api/agents/{id}/run` | `{prompt}` → `{reply, turns}`; 503 without `ANTHROPIC_API_KEY` |
| GET | `/api/public/agents/stats` | `{agents}` -- no token |

A server's stored `authHeader` is never returned; responses carry `hasAuthHeader` instead. Rejections come
back as `{"error": "..."}` like everywhere else.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | `9083` |
| `auth.jwks-uri` | `AUTH_JWKS_URI` | `http://localhost:9081/api/jwks.json` |
| `auth.token-versions-uri` | `AUTH_TOKEN_VERSIONS_URI` | `http://localhost:9081/internal/token-versions` |
| `spring.datasource.*` | `SPRING_DATASOURCE_*` | `jdbc:postgresql://localhost:5432/agent`, `agent` / `agent` |
| `anthropic.api-key` | `ANTHROPIC_API_KEY` | empty -- Run answers 503 until set |
| `anthropic.model` | `ANTHROPIC_MODEL` | `claude-opus-5-5` |
| `agents.max-turns` | `AGENTS_MAX_TURNS` | `8` |
| `agents.todo-mcp-url` | `AGENTS_TODO_MCP_URL` | `http://localhost:9082/mcp`; blank seeds no server |

## Limitations, on purpose

- **It will POST to any URL an owner types**, from inside the compose network, where the database and
  auth-service's `/internal` endpoints live. A real deployment puts an allow-list or an egress proxy in front
  of `McpTools`; blocking private ranges here would also block the built-in server. The caller's token is
  forwarded only to servers explicitly marked for it.
- **`authHeader` is stored in plain text**, like the database credentials in this demo.
- **The forwarded token is the owner's full authority.** The built-in todo server checks it like the REST API
  does, so agent-service's READ/WRITE gate is the only thing between a READ agent and `delete_todo`. That is
  the feature; it is also why the gate is checked on every call and never left to the prompt.
- **`token/` is the third copy** of the same three files. A shared module is the upgrade when a fourth
  service appears.

## Running it alone

```bash
docker compose up -d db
./mvnw -pl apps/agent-service spring-boot:run   # with auth-service and todo-service up
./mvnw -pl apps/agent-service test              # SQLite backed: needs nothing running, calls no model
```

Tests: `AgentRunnerTests` runs the permission rules end to end -- a scripted model against a real MCP server
mounted in the same context -- including a permission flipped mid-run and an agent trying to widen its own
access; `AgentServiceTests` the ownership rules and the activity log; `ApiContractTests` the 401, 404 and
503 shapes; `JwtVerifierTests` the token check against a throwaway JWKS.
