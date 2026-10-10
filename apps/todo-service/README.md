# todo-service

Per-account todos. Port **9082**.

It never calls auth-service to find out who is asking. It fetches the public half of auth-service's signing
key once, caches it, and verifies every token in process -- so there is no per-request hop, and holding only
the public key it could not mint a token of its own even if it wanted to.

It is the only service that writes to the `todo` database, and it has no access to the `auth` one.

## Packages

Split by subject rather than by layer -- one package holds its entity, service, controller and records
together.

```
todo/     Todo  TodoRepository  TodoService  TodoController  NewTodo  UpdateTodo  TodoResponse
mcp/      TodoMcpServer
stats/    StatsController  PublicStats
support/  TodoExceptionAdvice
```

Plus `com.demo.token` -- `JwtVerifier`, `Revocations`, `Caller` -- from the shared [`apps/token`](../token/README.md)
module, which agent-service and account-service use too. `todo` depends on it for who the caller is; `mcp`
depends on both and nothing points back. `stats` is the unauthenticated corner,
kept apart so the trust boundary shows up in the tree.

## Who is asking

`JwtVerifier` checks the token against the JWKS auth-service publishes. The key selector is pinned to RS256,
so a token asking for `none` or a symmetric algorithm is rejected before its signature is looked at, and the
claims verifier requires `sub` and `exp` -- a token with no expiry is refused outright rather than treated
as one that never expires.

What comes back is a `Caller`: an account id, a role and, for an agent token, the agent it is pinned to. The
role is a plain string, not an enum copied over from auth-service -- the verifying side shares no code with
the minting side on purpose -- and this service only ever asks two questions of it, both of which answer
"no" for anything unrecognised, so an unknown role, or a token with no `role` claim at all, lands on least
privilege instead of on a crash.

That is the one place this service differs from auth-service: the role is read **from the token's claims**,
not from a database row. Asking auth-service per request is exactly what the JWKS handoff exists to avoid,
so the cost is that a promotion or demotion takes effect here when the token is renewed rather than on the
next request. `auth.token-ttl` (default 30m) is that delay's upper bound.

## Permissions

See the [root README](../../README.md#roles) for the matrix that spans both services. What *this* service
enforces:

| Endpoint | ADMIN | MODERATOR | AGENT token | USER |
|---|---|---|---|---|
| `GET /api/todos` | everyone's | everyone's | 403 | own |
| `POST /api/todos` | own | own | 403 | own |
| `PUT /api/todos/{id}` | anyone's | own, else 404 | 403 | own |
| `PATCH /api/todos/{id}` | anyone's | own, else 404 | 403 | own |
| `DELETE /api/todos/{id}` | anyone's | own, else 404 | 403 | own |

A todo is always created for the caller whatever their role -- there is no "add this one to someone else".

The REST API takes **user tokens only**. An agent token (role `AGENT`, pinned to one of the user's agents,
good for 30 days) is 403 on every verb: its way in is `/mcp` through agent-service, where the agent's
READ/WRITE setting is applied and every call is logged. Over REST a READ agent could `DELETE` and skip both.
`/mcp` itself does take the agent token, because that is what agent-service forwards -- see below.

`TodoRepository` has an owner-scoped query for each operation (`findByOwnerOrderByIdAsc`,
`findByIdAndOwner`) plus the two unscoped reads the read-everyone roles need. `TodoService` is the only
thing that picks between them, through one private `writable(caller, id)` that every write goes through: a
caller that does not write everyone can reach no other account's row, and a moderator -- which reads
everyone but writes only its own -- lands on the owner-scoped lookup for every write exactly as a user does.

Someone else's todo id comes back **404, not 403**, so neither answer says whether that todo exists.

## Owners

The owner is the account **id** from the token's `sub`, not the email, so changing an email cannot orphan a
list.

It is in the todo response because a role that reads everyone would otherwise get a list it could not make
sense of. For everybody else it is their own id, which tells them nothing they did not already know.

## The MCP server

The same todos, reachable by an agent at `POST /mcp` (MCP Streamable HTTP, stateless). `TodoMcpServer` wires
the SDK's servlet transport in and registers four tools, each the thin MCP face of one `TodoService` method,
so the owner rules above apply to an agent exactly as they do to the browser:

| tool | `readOnlyHint` | does |
|---|---|---|
| `list_todos` | **true** | the caller's todos, one per line as `#id [x] title` |
| `add_todo {title}` | | `POST /api/todos` |
| `update_todo {id, title?, done?}` | | `PATCH /api/todos/{id}` |
| `delete_todo {id}` | | `DELETE /api/todos/{id}` |

Who is asking comes from the `Authorization` header on the MCP request -- agent-service forwards the token
the external agent connected with, the owner's own or an agent token minted for them with the owner as
subject -- and is checked by the same `JwtVerifier`. No or a bad token makes every
tool answer an error result rather than a list.

The annotation column is the point: agent-service's **READ** permission offers an agent only the tools a
server marks `readOnlyHint: true`, so leaving it off a tool that writes is the one thing this class must
never do. `/mcp` sits outside `/api`, so the web proxy never forwards it; only agent-service reaches it over
the compose network.

## Endpoints

| Method | Path | Body / notes |
|---|---|---|
| GET | `/api/todos` | the caller's own, or everyone's for a read-everyone role; an agent token is 403 |
| POST | `/api/todos` | `{title}`; an agent token is 403 |
| PUT | `/api/todos/{id}` | toggle done -- no body, so no read needed first; an agent token is 403 |
| PATCH | `/api/todos/{id}` | `{title?, done?}` -- only the fields sent change; an agent token is 403 |
| DELETE | `/api/todos/{id}` | an agent token is 403 |
| GET | `/api/public/todos/stats` | `{todos}` -- no token |
| POST | `/mcp` | MCP Streamable HTTP, see above -- compose network only; the one place an agent token is good |

Everything except the public count needs `Authorization: Bearer <token>`, and under `/api` it must be a
user's own. A null field in a PATCH means "leave it
alone", so renaming a todo cannot flip its done flag by omitting it. Every rejection comes back as
`{"error": "..."}` in the same shape auth-service uses, which `TodoExceptionAdvice` is responsible for.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | `9082` |
| `auth.jwks-uri` | `AUTH_JWKS_URI` | `http://localhost:9081/api/jwks.json` |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/todo` |
| `spring.datasource.username` / `.password` | `SPRING_DATASOURCE_*` | `todo` / `todo` |

## Running it alone

```bash
docker compose up -d db                  # it still needs a database
./mvnw -pl apps/todo-service spring-boot:run   # and auth-service up, for the JWKS
./mvnw -pl apps/todo-service test        # SQLite backed: needs nothing running
```

Tests: `TodoServiceTests` for the ownership and role rules, `ApiContractTests` for the 401 and the 403 an
agent token gets on every verb -- the token check itself is tested once, in `apps/token` -- and
`TodoMcpServerTests`, which drives `/mcp` with the real MCP client over real
HTTP: the annotation on `list_todos` and on nothing else, two callers who cannot see each other's todos, and
a missing token answered with an error result.
