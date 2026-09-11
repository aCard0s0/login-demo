# login-demo

Multi-module Maven project: two Spring Boot services plus a dependency-free Node frontend.

```
login-demo              the one script that runs this repo
pom.xml                 parent (packaging: pom)
compose.yaml            db + the three services
Dockerfile              one file, one build, three runtime stages
docker/initdb.sql       one database and one role per service
apps/auth-service       accounts, login, RS256 token issuing   :9081
apps/todo-service       per-account todos                      :9082
apps/web                static pages + /api proxy              :3000
```

Each service is split by what the code is *about*, not by which layer it sits in. A package holds one
subject end to end -- entity, service, controller and its request/response records together -- so a change
to one subject stays inside one folder:

```
auth-service  account/  Account  AccountRepository  AccountService  AccountController  + records
              session/  Session  SessionService  SessionController  TooManyAttemptsException  + records
              token/    Tokens  JwksController
              stats/    StatsController  PublicStats
              support/  AuthExceptionAdvice

todo-service  todo/     Todo  TodoRepository  TodoService  TodoController  + records
              token/    JwtVerifier
              stats/    StatsController  PublicStats
              support/  TodoExceptionAdvice
```

The arrows only point one way: `token` knows nothing about accounts (it signs an id, an email and a name,
not an `Account`), `account` uses `token` to resolve a caller, and `session` uses both to turn a password
into one. `stats` is the unauthenticated corner of each service, kept apart so the trust boundary is
visible in the tree rather than buried in a comment, and `support` holds the one cross-cutting piece each
service has -- the advice that renders every rejection as `{"error": "..."}`.

## Run

```bash
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

## Pages

| URL | What |
|---|---|
| `/` | Landing. The two public counts, no token needed. |
| `/login` | Log in, or create an account and drop straight in. |
| `/todos` | Your todos. Bounces to `/login` without a token. |
| `/account` | Change your name, email or password. |

Each page is its own file in `apps/web/public`, sharing `app.js` (token handling, the `api` helper, the
header) and `style.css`. `server.js` serves them from a fixed map of URL to filename, so a request cannot
walk out of `public/`.

## Accounts and passwords

Passwords are hashed with BCrypt (`spring-security-crypto`) and only the hash is stored. Registration
requires a name, an email and a password of at least 8 characters and at most 72 bytes -- BCrypt reads no
further than 72, so longer passwords are rejected rather than silently truncated. Emails are lowercased and
must be unique; the unique index on `accounts.email` is the real guard, so two simultaneous registrations
still come back as a 400, not a 500.

A login for an unknown email still runs one hash comparison, so a missing account takes the same time as a
wrong password. Five failed logins for one email inside 15 minutes lock that email out with a 429, correct
password included. The counter is per email rather than per IP because every browser request reaches the
services from the Node proxy and would otherwise share a single counter -- the trade is that someone who
knows an address can lock it out on purpose.

Editing an account (`PUT /api/accounts/me`) always requires the current password, even to change only the
name, so a borrowed tab cannot quietly take an account over.

## Tokens

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

## Per-user todos

Every query in `TodoRepository` is scoped by owner (`findByOwnerOrderByIdAsc`, `findByIdAndOwner`,
`deleteByIdAndOwner`). There is no repository method that can reach another account's row, so passing
someone else's todo id returns 404 rather than leaking it.

The owner is the account **id** taken from the token's `sub`, not the email, so changing an email does not
orphan a list.

## Endpoints

| Service | Method | Path | Notes |
|---|---|---|---|
| auth | POST | `/api/accounts` | `{name, email, password}` -> 201 `{id, name, email}` |
| auth | POST | `/api/login` | `{email, password}` -> `{token, name, email}`, 401, or 429 once locked out |
| auth | GET | `/api/accounts/me` | the caller's own account |
| auth | PUT | `/api/accounts/me` | `{name, email, currentPassword, newPassword?}` |
| auth | GET | `/api/public/stats` | `{accounts}` -- no token |
| auth | GET | `/api/jwks.json` | the public signing key -- no token |
| todo | GET | `/api/todos` | |
| todo | POST | `/api/todos` | `{title}` |
| todo | PUT | `/api/todos/{id}` | toggle done |
| todo | PATCH | `/api/todos/{id}` | `{title?, done?}` -- only the fields sent change |
| todo | DELETE | `/api/todos/{id}` | |
| todo | GET | `/api/public/todos/stats` | `{todos}` -- no token |

Everything except the two public endpoints and the JWKS needs `Authorization: Bearer <token>`. The token
travels in the header rather than a query parameter so it stays out of access logs and Referer headers.
Rejected input comes back as `{"error": "..."}` from both services, which is what the pages render.

The Node server proxies `/api/todos*` and `/api/public/todos*` to todo-service and the rest of `/api/*` to
auth-service, so the browser stays on one origin and neither service needs CORS config.

Ports 9081/9082 rather than 8081/8082: Docker holds those on this machine. Override with `server.port`, and
point the frontend elsewhere with `AUTH_URL` / `TODO_URL`. todo-service finds the signing key through
`auth.jwks-uri` (`AUTH_JWKS_URI` in compose), and both services take `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` from the environment.
