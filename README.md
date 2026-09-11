# login-demo

Multi-module Maven project: two Spring Boot services plus a dependency-free Node frontend. Accounts and
logins in one service, todos in another, and an RS256 token handoff between them instead of a per-request
call.

```
login-demo              the one script that runs this repo
pom.xml                 parent (packaging: pom)
compose.yaml            db + the three services
Dockerfile              one file, one build, three runtime stages
docker/initdb.sql       one database and one role per service
.env.example            the admin credentials compose expects in .env
apps/auth-service       accounts, login, roles, token issuing   :9081
apps/todo-service       per-account todos                       :9082
apps/web                static pages + /api proxy               :3000
```

Each service documents itself:

| | What it owns | README |
|---|---|---|
| auth-service | accounts, passwords, roles, the signing key | [apps/auth-service](apps/auth-service/README.md) |
| todo-service | todos, and verifying tokens locally | [apps/todo-service](apps/todo-service/README.md) |
| web | the four pages and the one-origin proxy | [apps/web](apps/web/README.md) |

## Package convention

Both services are split by what the code is *about*, not by which layer it sits in. A package holds one
subject end to end -- entity, service, controller and its request/response records together -- so a change
to one subject stays inside one folder. Each service README has its own tree.

Two rules hold across both:

- **The arrows point one way.** `token` knows nothing about accounts (it signs an id, an email, a name and a
  role -- not an `Account`), `account` uses `token` to resolve a caller, and `session` uses both to turn a
  password into one.
- **`stats` is the unauthenticated corner** of each service, kept apart so the trust boundary is visible in
  the tree rather than buried in a comment, and **`support`** holds the one cross-cutting piece each service
  has: the advice that renders every rejection as `{"error": "..."}`.

## Run

```bash
cp .env.example .env      # then change the admin password
./login-demo start
```

Open http://localhost:3000. Four containers: `db`, `auth-service`, `todo-service`, `web`.

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
node apps/web/server.js
```

### Databases

Postgres, with a database and a role per service (`auth`/`auth`, `todo`/`todo`, created by
`docker/initdb.sql`), so neither service can read the other's tables even by accident. The credentials are
development values and the `db` container publishes no port; change them before this goes anywhere real.

Tests are the exception: they run against a throwaway SQLite file so `./mvnw test` needs nothing installed
or running. That means the test suite does not exercise the same database the services actually use.

## Roles

Four of them, on the account row and carried in the token: `ADMIN`, `MODERATOR`, `AGENT`, `USER`.

| | read own | write own | read everyone | write everyone | change roles |
|---|---|---|---|---|---|
| **ADMIN** | yes | yes | yes | yes | yes |
| **MODERATOR** | yes | yes | yes | no | no |
| **AGENT** | yes | yes | no | no | no |
| **USER** | yes | yes | no | no | no |

`AGENT` is reserved and currently behaves exactly like `USER`; nothing grants it yet.

Endpoint by endpoint:

| Service | Endpoint | ADMIN | MODERATOR | AGENT | USER |
|---|---|---|---|---|---|
| auth | `GET`/`PUT /api/accounts/me` | own | own | own | own |
| auth | `GET /api/accounts` | everyone | everyone | 403 | 403 |
| auth | `PUT /api/accounts/{id}/role` | any account | 403 | 403 | 403 |
| todo | `GET /api/todos` | everyone's | everyone's | own | own |
| todo | `POST /api/todos` | own | own | own | own |
| todo | `PUT`/`PATCH`/`DELETE /api/todos/{id}` | anyone's | own, else 404 | own | own |

A todo belonging to someone else comes back **404, not 403**, so neither answer says whether it exists.

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

An admin changing *another* account's name, email or password is deliberately not implemented: editing an
account requires its current password, and bypassing that would be account takeover rather than
administration.

### The admin account

Seeded into auth-service's database at startup from `ADMIN_EMAIL` and `ADMIN_PASSWORD`, which compose reads
from `.env`. `.env` is gitignored; `.env.example` is the committed stand-in. Compose refuses to start the
stack if either value is missing, rather than coming up with nobody in charge.

Seeding is **create-only**: an address that already exists is promoted to `ADMIN`, but its password is left
exactly as it is, so a restart cannot quietly reset a password the admin has since changed and a stale
`.env` cannot hand the account back. Change it from `/account` like any other account; to start over,
`./login-demo down --volumes`.

Details, including why it is not in `docker/initdb.sql`, are in the
[auth-service README](apps/auth-service/README.md#the-seeded-admin).

## How the services trust each other

auth-service generates an RSA keypair at startup and signs RS256 JWTs with the private half. It publishes
only the public half, at `/api/jwks.json`. todo-service fetches that once, caches it, and verifies every
token in process -- so it never calls auth-service per request, and holding only the public key it could
never mint a token of its own. Its key selector is pinned to RS256, so a token asking for `none` or a
symmetric algorithm is rejected before its signature is looked at.

The consequences worth knowing:

- **There is no logout endpoint.** A signed token is good until it expires; logging out is the browser
  dropping the token it holds. `auth.token-ttl` (default 30m) is the real bound.
- **A restart invalidates every token in flight**, because it mints a new keypair. Accounts and todos are
  not affected. Nothing is written to disk, so there is no private key in this repo to leak.
- The key carries a `kid`, so adding a second key later is additive rather than a breaking change.

## Endpoints

| Service | Method | Path | Token |
|---|---|---|---|
| auth | POST | `/api/accounts` | no -- this is registration |
| auth | POST | `/api/login` | no |
| auth | GET · PUT | `/api/accounts/me` | yes |
| auth | GET | `/api/accounts` | yes -- admin and moderator only |
| auth | PUT | `/api/accounts/{id}/role` | yes -- admin only |
| auth | GET | `/api/public/stats` | no |
| auth | GET | `/api/jwks.json` | no |
| todo | GET · POST | `/api/todos` | yes |
| todo | PUT · PATCH · DELETE | `/api/todos/{id}` | yes |
| todo | GET | `/api/public/todos/stats` | no |

Request and response bodies are in each service's README:
[auth-service](apps/auth-service/README.md#endpoints), [todo-service](apps/todo-service/README.md#endpoints).

The token travels in the `Authorization: Bearer <token>` header rather than a query parameter so it stays
out of access logs and Referer headers. Rejected input comes back as `{"error": "..."}` from both services,
which is what the pages render.

The Node server proxies `/api/todos*` and `/api/public/todos*` to todo-service and the rest of `/api/*` to
auth-service, so the browser stays on one origin and neither service needs CORS config.

Ports 9081/9082 rather than 8081/8082: Docker holds those on this machine. Override with `server.port`, and
point the frontend elsewhere with `AUTH_URL` / `TODO_URL`. todo-service finds the signing key through
`auth.jwks-uri` (`AUTH_JWKS_URI` in compose), and both services take `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` from the environment.
