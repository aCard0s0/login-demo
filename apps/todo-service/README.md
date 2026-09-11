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
token/    JwtVerifier  Caller
stats/    StatsController  PublicStats
support/  TodoExceptionAdvice
```

`todo` depends on `token` for who the caller is; nothing points back. `stats` is the unauthenticated corner,
kept apart so the trust boundary shows up in the tree.

## Who is asking

`JwtVerifier` checks the token against the JWKS auth-service publishes. The key selector is pinned to RS256,
so a token asking for `none` or a symmetric algorithm is rejected before its signature is looked at, and the
claims verifier requires `sub` and `exp` -- a token with no expiry is refused outright rather than treated
as one that never expires.

What comes back is a `Caller`: an account id and a role. The role is a plain string, not an enum copied over
from auth-service. The two services share no code on purpose, and this one only ever asks two questions of
it -- both of which answer "no" for anything unrecognised, so an unknown role, or a token with no `role`
claim at all, lands on least privilege instead of on a crash.

That is the one place this service differs from auth-service: the role is read **from the token's claims**,
not from a database row. Asking auth-service per request is exactly what the JWKS handoff exists to avoid,
so the cost is that a promotion or demotion takes effect here when the token is renewed rather than on the
next request. `auth.token-ttl` (default 30m) is that delay's upper bound.

## Permissions

See the [root README](../../README.md#roles) for the matrix that spans both services. What *this* service
enforces:

| Endpoint | ADMIN | MODERATOR | AGENT | USER |
|---|---|---|---|---|
| `GET /api/todos` | everyone's | everyone's | own | own |
| `POST /api/todos` | own | own | own | own |
| `PUT /api/todos/{id}` | anyone's | own, else 404 | own | own |
| `PATCH /api/todos/{id}` | anyone's | own, else 404 | own | own |
| `DELETE /api/todos/{id}` | anyone's | own, else 404 | own | own |

A todo is always created for the caller whatever their role -- there is no "add this one to someone else".

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

## Endpoints

| Method | Path | Body / notes |
|---|---|---|
| GET | `/api/todos` | the caller's own, or everyone's for a read-everyone role |
| POST | `/api/todos` | `{title}` |
| PUT | `/api/todos/{id}` | toggle done -- no body, so no read needed first |
| PATCH | `/api/todos/{id}` | `{title?, done?}` -- only the fields sent change |
| DELETE | `/api/todos/{id}` | |
| GET | `/api/public/todos/stats` | `{todos}` -- no token |

Everything except the last needs `Authorization: Bearer <token>`. A null field in a PATCH means "leave it
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

Tests: `TodoServiceTests` for the ownership and role rules, `JwtVerifierTests` for the token check -- the
only thing standing between a stranger and somebody's todo list, so it runs against a real throwaway JWKS
server rather than a mock.
