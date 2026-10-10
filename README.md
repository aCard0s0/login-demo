# login-demo

Multi-module Maven project: four Spring Boot services plus a dependency-free Node frontend. Accounts and
logins in one service, todos in another, user-owned agents -- each an MCP server with permissioned tools that
Claude Code or any MCP client connects to -- in a third, money accounts that users and their agents move funds
between in a fourth, and an RS256 token handoff between them instead of a per-request call.

```
login-demo              the one script that runs this repo
pom.xml                 parent (packaging: pom)
compose.yaml            db + the five services
Dockerfile              one file, one build, five runtime stages
docker/initdb.sql       one database and one role per service
.env.example            the admin credentials and OAuth client secrets compose reads from .env
libs/auth-client        verifying those tokens: the one copy the three services below share
libs/web-errors         the {error} body every service answers a rejection with
libs/mcp-server         the stateless /mcp endpoint and tool-argument parsing
apps/auth-service       accounts, login, OAuth, roles, tokens    :9081
apps/todo-service       per-account todos, and their MCP server  :9082
apps/agent-service      agents, their MCP servers, permissions   :9083
apps/account-service    money accounts, transfers, agent grants  :9084
apps/web                static pages + /api and /mcp proxy      :3000
```

Each service documents itself:

| | What it owns | README |
|---|---|---|
| auth-service | accounts, passwords, roles, OAuth sign-in, the signing key | [apps/auth-service](apps/auth-service/README.md) |
| todo-service | todos, verifying tokens locally, and the `/mcp` server agents reach them through | [apps/todo-service](apps/todo-service/README.md) |
| agent-service | agents as MCP servers: the servers each may use, READ/WRITE enforced per tool call | [apps/agent-service](apps/agent-service/README.md) |
| account-service | money accounts for users and their agents, transfers, READ/WRITE grants per agent, and its `/mcp` | [apps/account-service](apps/account-service/README.md) |
| web | the pages and the one-origin proxy | [apps/web](apps/web/README.md) |
| auth-client | `JwtVerifier`, `Revocations` and `Caller`: a plain jar, no service, that todo-, agent- and account-service depend on | [libs/auth-client](libs/auth-client/README.md) |
| web-errors | `ErrorBodyAdvice`: every rejection, MVC's own included, as `{"error": "..."}`; all four services extend it | [libs/web-errors](libs/web-errors/README.md) |
| mcp-server | `McpEndpoint` and `Args`: the `/mcp` servlet todo- and account-service serve their tools through, and exact argument parsing agent-service shares too | [libs/mcp-server](libs/mcp-server/README.md) |

## Package convention

The services are split by what the code is *about*, not by which layer it sits in. A package holds one
subject end to end -- entity, service, controller and its request/response records together -- so a change
to one subject stays inside one folder. Each service README has its own tree.

Two rules hold across all of them:

- **The arrows point one way.** `token` knows nothing about accounts (it signs an id, an email, a name and a
  role -- not an `Account`), `account` uses `token` to resolve a caller, and `session` uses both to turn a
  password into one. The verifying half of `token` lives once, in the `libs/auth-client` library, and the three
  services that check tokens in process depend on it rather than carrying a copy each.
- **`stats` is the unauthenticated corner** of each service, kept apart so the trust boundary is visible in
  the tree rather than buried in a comment, and **`support`** holds the one cross-cutting piece each service
  has: the advice that renders every rejection as `{"error": "..."}`. It extends `ErrorBodyAdvice` from
  `libs/web-errors` and adds only that service's domain rejections.

## Run

```bash
cp .env.example .env      # then change the admin password
./login-demo start
```

Open http://localhost:3000. Six containers: `db`, `auth-service`, `todo-service`, `agent-service`, `account-service`, `web`.

`./login-demo` with no arguments prints every command it has; `./login-demo <command> --help` explains one.
The ones worth knowing: `dev` runs the stack in the foreground, `status` and `logs` say what it is doing,
`verify` smoke tests a running stack end to end, `doctor` says what is missing, and `test` runs the Maven
suite without needing anything up. `-n` dry-runs anything and prints the commands it would have run.

Underneath it is plain compose, so `docker compose up --build` works just as well.

Only `web` publishes a port. The services and the database talk over the compose network, which is the same
shape the browser sees in production: one origin, everything else unreachable from outside. `docker compose
down -v` also drops the database volume.

To run without Docker you still need the database, so start that one container and point the services at
localhost -- which is what their `application.properties` already say:

```bash
docker compose up -d db
./mvnw package
java -jar apps/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar &
java -jar apps/todo-service/target/todo-service-0.0.1-SNAPSHOT.jar &
java -jar apps/agent-service/target/agent-service-0.0.1-SNAPSHOT.jar &
java -jar apps/account-service/target/account-service-0.0.1-SNAPSHOT.jar &
node apps/web/server.js
```

### Databases

Postgres, with a database and a role per service (`auth`/`auth`, `todo`/`todo`, `agent`/`agent`,
`account`/`account`, created by `docker/initdb.sql`), so no service can read another's tables even by accident. The credentials are
development values and the `db` container publishes no port; change them before this goes anywhere real.

`docker/initdb.sql` runs only when the volume is created. A stack that predates agent-service or
account-service lacks their databases, so either start over with `./login-demo db reset` or add them to the
volume you have:

```bash
./login-demo exec db psql -U postgres -c "CREATE USER agent WITH PASSWORD 'agent';" -c "CREATE DATABASE agent OWNER agent;"
```

```bash
./login-demo exec db psql -U postgres -c "CREATE USER account WITH PASSWORD 'account';" -c "CREATE DATABASE account OWNER account;"
```

Tests are the exception: they run against a throwaway SQLite file so `./mvnw test` needs nothing installed
or running. That means the test suite does not exercise the same database the services actually use.

### Resources

Every container is capped, and the caps are measured rather than guessed. Under a burst of 200
authenticated reads and 40 logins the first four sit at 490 MiB with nothing OOM-killed; agent-service was
measured idle after `verify`:

| Container | `mem_limit` | measured | `cpus` |
|---|---|---|---|
| auth-service | 288m | ~205 MiB (71%) | 1.0 |
| todo-service | 288m | ~206 MiB (71%) | 1.0 |
| agent-service | 320m | ~217 MiB (68%) | 1.0 |
| account-service | 288m | not yet measured | 1.0 |
| db | 192m | ~68 MiB (35%) | 0.5 |
| web | 64m | ~18 MiB (27%) | 0.5 |

The JVM services are the floor, not the ceiling: a JVM's resident size is mostly metaspace, code cache,
thread stacks and the GC's own structures, none of which shrink much for a small application. Both run
with `-XX:MaxRAMPercentage=50`, so the heap is half the limit and the rest of that list has somewhere to
live -- the JVM reads the cgroup limit, so `mem_limit` is what actually sizes the heap. Postgres cannot go
much under 192m either, because `shared_buffers` alone defaults to 128MB.

`cpus` matters most at startup, which is the only CPU-hungry moment either service has; BCrypt at cost 10
is the other, at roughly a tenth of a second per login.

To re-measure after a change:

```bash
./login-demo start
./login-demo verify
docker stats --no-stream $(docker compose -p login-demo ps -q)
docker inspect login-demo-auth-service-1 --format '{{.State.OOMKilled}}'
```

## Roles

Four of them, on the account row and carried in the token: `ADMIN`, `MODERATOR`, `AGENT`, `USER`.

| | read own | write own | read everyone | write everyone | change roles |
|---|---|---|---|---|---|
| **ADMIN** | yes | yes | yes | yes | yes |
| **MODERATOR** | yes | yes | yes | no | no |
| **AGENT** | yes | yes | no | no | no |
| **USER** | yes | yes | no | no | no |

`AGENT` behaves exactly like `USER` and is never on an account row: it is the role stamped on the long-lived
token minted for one of a user's agents, with the owner as subject, so a connecting agent acts as its owner
and nothing more. See [Agents](#agents).

Endpoint by endpoint:

| Service | Endpoint | ADMIN | MODERATOR | AGENT | USER |
|---|---|---|---|---|---|
| auth | `GET`/`PUT /api/accounts/me` | own | own | 403 | own |
| auth | `GET /api/accounts` | everyone | everyone | 403 | 403 |
| auth | `PUT /api/accounts/{id}/role` | any account | 403 | 403 | 403 |
| auth | `PUT /api/accounts/{id}/suspended` · `POST /api/accounts/{id}/revoke` | any account | 403 | 403 | 403 |
| todo | `GET /api/todos` | everyone's | everyone's | 403; over MCP its owner's | own |
| todo | `POST /api/todos` | own | own | 403; over MCP its owner's, if WRITE | own |
| todo | `PUT`/`PATCH`/`DELETE /api/todos/{id}` | anyone's | own, else 404 | 403; over MCP its owner's, if WRITE | own |
| agent | everything under `/api/agents` | own | own | 403 | own |
| account | `GET /api/bank/accounts` · `GET .../{id}` · `GET .../{id}/transfers` | everyone's | everyone's | 403; over MCP its own + granted | own + its agents' |
| account | `POST /api/bank/accounts/{id}/transfers` | anyone's | own, else 404 | 403; over MCP its own + WRITE grants | own + its agents' |
| account | `POST /api/bank/accounts` · `.../deposit` · `PUT`/`DELETE .../permissions/{agentId}` | anyone's | own, else 404 | 403 | own + its agents' |

A todo -- or an agent -- belonging to someone else comes back **404, not 403**, so neither answer says whether
it exists. Agents are the one thing no role sees across accounts, an admin included.

**An agent token is 403 on every `/api` endpoint, in every service.** Its only way in is `/mcp` through
agent-service, where the agent's READ/WRITE setting and activity log apply; over REST a READ agent could
delete a todo with its 30-day token, or change its owner's password, and skip both. The MCP endpoints of
todo-service and account-service are the one place an agent token is good, and only agent-service reaches
them. The endpoints that take no token at all -- registration, login, the OAuth redirects, `/api/public/*`,
`/api/jwks.json` -- are unaffected, since there is no token to refuse.

Registration always produces a `USER` -- `POST /api/accounts` has no role field to ask with, and
`PUT /api/accounts/me` cannot change one. `PUT /api/accounts/{id}/role` is the single door off `USER`, and
only an admin may open it. An admin cannot demote itself, because the last one doing so would leave nobody
able to promote anybody ever again.

The two services answer "what role is this?" differently, on purpose:

- **auth-service reads the account row** on every request, so a promotion or demotion bites at once, on a
  token the holder already has.
- **todo-service reads the token's claim**, because asking auth-service per request is exactly what the
  JWKS handoff exists to avoid. A role change lands there when the token is renewed -- within
  `auth.token-ttl`, 30 minutes by default.

### Suspending and revoking

From `/admin`, an admin can **suspend** an account (it cannot log in, by password or provider, and every
token it holds dies, until it is reactivated) or **revoke its access** (every token it holds dies; it can
log straight back in). An admin cannot suspend itself.

Both work through a per-account token version, stamped into every token as `ver` and bumped on revoke or
suspend. auth-service compares it on every request, so both bite there at once. todo-, agent- and
account-service poll `/internal/token-versions` at most every 10 seconds and turn away any token older than
the account's last revocation. That path is outside `/api`, so the web proxy never forwards it; if
auth-service is unreachable they keep the last list they had.

An owner can also revoke **one agent's tokens** alone, with **Revoke tokens** on the agent's page
(`POST /api/agents/{id}/token/revoke`). That bumps a per-agent version auth-service keeps and stamps into
agent tokens as `agentVer`; the same feed publishes it under `agent:<id>`, so an agent key can never collide
with an account id. The owner's login and their other agents are untouched. An agent token minted before
`agentVer` existed is refused outright, since it cannot be told from a revoked one.

An admin changing *another* account's name, email or password is deliberately not implemented: editing an
account requires its current password, and bypassing that would be account takeover rather than
administration.

### The admin account

Seeded into auth-service's database at startup from `ADMIN_EMAIL` and `ADMIN_PASSWORD`, which compose reads
from `.env`. `.env` is gitignored; `.env.example` is the committed stand-in. Compose refuses to start the
stack if either value is missing, rather than coming up with nobody in charge -- and likewise without
`INTERNAL_SECRET` and `AUTH_HEADER_KEY`, the two secrets agent-service needs.

Seeding is **create-only**: an address that already exists is promoted to `ADMIN`, but its password is left
exactly as it is, so a restart cannot quietly reset a password the admin has since changed and a stale
`.env` cannot hand the account back. Change it from `/account` like any other account; to start over,
`./login-demo down --volumes`.

Details, including why it is not in `docker/initdb.sql`, are in the
[auth-service README](apps/auth-service/README.md#the-seeded-admin).

## Agents

From `/agents`, any user creates as many **agents** as they like. An agent is a name, instructions, a list of
**MCP servers** each marked **READ** or **WRITE**, and one setting for how far it may reach into the owner's
*other* agents. No model runs in this stack: each agent **is an MCP server**, at `/mcp?agent=<id>`, and
Claude Code, Cursor or any MCP client connects to it and gets exactly the tools those permissions allow.

```bash
claude mcp add --transport http todos "http://localhost:3000/mcp?agent=<id>" --header "Authorization: Bearer <token>"
```

The token is either the owner's own login token (30 minutes) or one made with **Create token** on the
agent's page: minted by auth-service with role `AGENT`, the owner as subject and the agent pinned by claim,
good for 30 days, shown once, and killed early by **Revoke tokens** on that page (this agent's alone) or by
**Revoke access** on the account (every token the owner holds).
Step by step, for Claude Code, Cursor, VS Code and plain curl: [docs/connect-an-agent.md](docs/connect-an-agent.md).

The permission is the service's, not the connecting agent's. **READ** offers only the tools that only read
and refuses any other; **WRITE** offers them all. Which tools only read is the server's own `readOnlyHint`
for the servers the deployment trusts (`AGENTS_TRUSTED_SERVER_URLS`, and the built-in todo one) and, for
any other server an owner adds, exactly the tools the owner lists as read-only on that server row -- a
server an owner typed in can annotate anything it likes, so its word is not taken. Every tool call is checked
again by agent-service against the row as it is saved *at that moment*, so flipping a server from WRITE to
READ on the page while an agent is connected is obeyed from its next call. What ran and what was refused is in
the agent's activity log on the same page.

Every new agent starts with the built-in **todos** server as READ: todo-service exposes its todos over MCP at
`/mcp`, with `list_todos` read-only and `add_todo`, `update_todo`, `delete_todo` not. The agent acts **as its
owner** -- the token it connected with is forwarded to that server -- so it can only ever see the owner's
todos. Any other Streamable HTTP MCP server can be added by URL, with an optional authorization header, which
is encrypted at rest with `AUTH_HEADER_KEY` from `.env` (required, like the admin credentials).

An agent reads or changes the owner's other agents only when its **other agents** setting says READ or WRITE,
through built-in tools scoped to the same owner; it can never change its own setup.

A server URL an owner types is checked before it is saved and again before every connection: the
deployment's own servers (`AGENTS_TRUSTED_SERVER_URLS`, and the built-in todo one) pass by name, and any
other must resolve to a public address -- nothing loopback, private, link-local (the cloud metadata address
lives there), carrier-grade NAT or multicast, and no bare compose service name -- so agent-service cannot be
pointed at the database or auth-service's `/internal` endpoints from inside its own network. Redirects are
not followed. Details, the activity kinds and the deliberate limitations are in the
[agent-service README](apps/agent-service/README.md).

## Signing in with Google or GitHub

Off by default, and on per provider: set `OAUTH_<PROVIDER>_ENABLED=true` together with that provider's
client id and secret in `.env`. Either credential missing counts as off, so a half-filled `.env` draws no
button rather than a button that leads to a provider error page. `.env.example` lists the settings, and
[getting a client id and secret](apps/auth-service/README.md#getting-a-client-id-and-secret) walks through
both consoles.

Register `http://localhost:3000/api/oauth/<provider>/callback` as the callback URL with the provider,
exactly as written. Serving the frontend from another address means changing `OAUTH_REDIRECT_BASE_URL` and
the registered URI together -- a provider rejects a `redirect_uri` it does not already know.

**Signing up and logging in are the same button.** A provider identity is matched to an account by
*verified* email: if that address is already registered it is that account, password login and all;
otherwise an account is created for it. An unverified address is refused, since accepting one would let
anyone who can claim an address at a provider walk into the account that already owns it here.

The flow is the OAuth 2.0 authorization-code flow written out by hand rather than
`spring-boot-starter-oauth2-client`, which would install the security filter chain these services
deliberately do not have. The state-cookie guard, the fragment handoff, and why an account created this way
has no usable password are in the
[auth-service README](apps/auth-service/README.md#signing-in-with-google-or-github).

## How the services trust each other

auth-service generates an RSA keypair at startup and signs RS256 JWTs with the private half. It publishes
only the public half, at `/api/jwks.json`. todo-service fetches that once, caches it, and verifies every
token in process -- so it never calls auth-service per request, and holding only the public key it could
never mint a token of its own. Its key selector is pinned to RS256, so a token asking for `none` or a
symmetric algorithm is rejected before its signature is looked at.

The consequences worth knowing:

- **There is no logout endpoint.** A signed token is good until it expires; logging out is the browser
  dropping the token it holds. `auth.token-ttl` (default 30m) is the real bound -- unless an admin revokes
  the account's tokens, see [Suspending and revoking](#suspending-and-revoking).
- **A restart invalidates every token in flight**, because it mints a new keypair. Accounts and todos are
  not affected. Nothing is written to disk, so there is no private key in this repo to leak.
- The key carries a `kid`, so adding a second key later is additive rather than a breaking change.

## Endpoints

| Service | Method | Path | Token |
|---|---|---|---|
| auth | POST | `/api/accounts` | no -- this is registration |
| auth | POST | `/api/login` | no |
| auth | GET · PUT | `/api/accounts/me` | yes -- user tokens only; an agent token is 403 on all of `/api/accounts` |
| auth | GET | `/api/accounts` | yes -- admin and moderator only |
| auth | PUT | `/api/accounts/{id}/role` | yes -- admin only |
| auth | PUT | `/api/accounts/{id}/suspended` | yes -- admin only |
| auth | POST | `/api/accounts/{id}/revoke` | yes -- admin only |
| auth | GET | `/internal/token-versions` | no -- compose network only, never proxied; accounts by id, agents as `agent:<id>` |
| auth | POST | `/internal/agent-tokens` · `/internal/agent-tokens/{agentId}/revoke` | `X-Internal-Secret` -- compose network only, never proxied; agent-service asks |
| auth | GET | `/api/oauth/providers` | no |
| auth | GET | `/api/oauth/{provider}/start` · `/callback` | no -- 302s the browser walks through |
| auth | GET | `/api/public/stats` | no |
| auth | GET | `/api/jwks.json` | no |
| todo | GET · POST | `/api/todos` | yes -- user tokens only; an agent token is 403 on all of `/api/todos` |
| todo | PUT · PATCH · DELETE | `/api/todos/{id}` | yes -- user tokens only |
| todo | GET | `/api/public/todos/stats` | no |
| todo | POST | `/mcp` | yes -- MCP, agent or login token as forwarded by agent-service, compose network only, never proxied |
| agent | GET · POST | `/api/agents` | yes -- user tokens only; an agent token is 403 on all of `/api/agents` |
| agent | GET · PATCH · DELETE | `/api/agents/{id}` | yes -- owner only |
| agent | POST | `/api/agents/{id}/servers` · `PATCH`/`DELETE .../servers/{sid}` | yes -- owner only |
| agent | GET | `/api/agents/{id}/activity` | yes -- owner only |
| agent | POST | `/api/agents/{id}/token` | yes -- owner only; a 30-day token for connecting as this agent |
| agent | POST | `/api/agents/{id}/token/revoke` | yes -- owner only; kills every token made for this one agent |
| agent | POST | `/mcp?agent={id}` | yes -- MCP; owner's token or the agent's own; proxied |
| agent | GET | `/api/public/agents/stats` | no |
| account | GET · POST | `/api/bank/accounts` | yes -- user tokens only; an agent token is 403 on all of `/api/bank` |
| account | GET | `/api/bank/accounts/{id}` · `/api/bank/accounts/{id}/transfers` | yes |
| account | POST | `/api/bank/accounts/{id}/deposit` | yes -- owner or admin, never an agent token |
| account | POST | `/api/bank/accounts/{id}/transfers` | yes -- owner or admin; an agent transfers over MCP only |
| account | PUT · DELETE | `/api/bank/accounts/{id}/permissions/{agentId}` | yes -- owner or admin |
| account | POST | `/mcp` | yes -- MCP, agent tokens only, compose network only, never proxied |
| account | GET | `/api/public/bank/stats` | no |

Request and response bodies are in each service's README:
[auth-service](apps/auth-service/README.md#endpoints), [todo-service](apps/todo-service/README.md#endpoints),
[agent-service](apps/agent-service/README.md#endpoints), [account-service](apps/account-service/README.md#endpoints).

The token travels in the `Authorization: Bearer <token>` header rather than a query parameter so it stays
out of access logs and Referer headers. Rejected input comes back as `{"error": "..."}` from both services,
which is what the pages render.

The Node server proxies `/mcp`, `/api/agents*` and `/api/public/agents*` to agent-service, `/api/bank*` and
`/api/public/bank*` to account-service (whole path segments, so `/api/banking` is not the bank's), `/api/todos*` and `/api/public/todos*` to todo-service and the rest of
`/api/*` to auth-service, so the browser stays on
one origin, no service needs CORS config, and an external agent reaches `/mcp` through the same port.

Ports 9081-9084 rather than 8081-8084: Docker holds those on this machine. Override with `server.port`, and
point the frontend elsewhere with `AUTH_URL` / `TODO_URL` / `AGENT_URL` / `ACCOUNT_URL`. todo-service,
agent-service and account-service find the signing key through `auth.jwks-uri` (`AUTH_JWKS_URI` in compose) and revocations
through `auth.token-versions-uri` (`AUTH_TOKEN_VERSIONS_URI`); agent-service finds the built-in todo MCP
server through `agents.todo-mcp-url` (`AGENTS_TODO_MCP_URL`), the other servers it may reach by compose name
through `agents.trusted-server-urls` (`AGENTS_TRUSTED_SERVER_URLS`) and the token minter through
`auth.agent-tokens-uri` (`AUTH_AGENT_TOKENS_URI`); and all four services take
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` from the environment.
