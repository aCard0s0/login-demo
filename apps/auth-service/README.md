# auth-service

Accounts, login, roles, and the RS256 keypair every other service verifies against. Port **9081**.

It is the only service that writes to the `auth` database, and the only holder of the private signing key.
Nothing here calls todo-service; the traffic goes the other way, and only for the public key.

## Packages

Split by subject rather than by layer -- one package holds its entity, service, controller and records
together, so a change to one subject stays in one folder.

```
account/  Account  Role  AccountRepository  AccountService  AccountController  AdminSeeder
          AccountResponse  NewAccount  UpdateAccount  RoleChange
session/  Session  SessionService  SessionController  TooManyAttemptsException
          LoginRequest  LoginResponse
token/    Tokens  JwksController
stats/    StatsController  PublicStats
support/  AuthExceptionAdvice
```

The dependencies point one way: `token` <- `account` <- `session`. `Tokens.issue` takes an id, an email, a
name and a role rather than an `Account`, which is what keeps `token` free of the account package. `stats`
is the unauthenticated corner, kept apart so the trust boundary shows up in the tree.

## Roles

The role lives on the account row, stored as its name (`@Enumerated(EnumType.STRING)`) so reordering the
enum cannot silently re-grade anybody. See the [root README](../../README.md#roles) for the matrix that
spans both services; what *this* service enforces is:

| Endpoint | ADMIN | MODERATOR | AGENT | USER |
|---|---|---|---|---|
| `GET /api/accounts/me` | own | own | own | own |
| `PUT /api/accounts/me` | own | own | own | own |
| `GET /api/accounts` | everyone | everyone | 403 | 403 |
| `PUT /api/accounts/{id}/role` | any account | 403 | 403 | 403 |

Registration always produces a `USER`: `POST /api/accounts` has no role field to ask with, and
`PUT /api/accounts/me` cannot change one. `PUT /api/accounts/{id}/role` is the single door off `USER`.

Two rules worth knowing:

- **An admin cannot demote itself.** The last one doing so would leave nobody able to promote anybody ever
  again, so `AccountService.changeRole` refuses it rather than trusting whoever writes the next controller.
- **The role is read from the account row, not from the token's claims.** A promotion or demotion takes
  effect on the next request, on a token the holder already has. (todo-service is the opposite -- see its
  README for why.)

An admin changing *another* account's name, email or password is deliberately not implemented:
`PUT /api/accounts/me` requires the current password even to change only the name, and admin-bypassing that
would be account takeover rather than administration.

### The seeded admin

`AdminSeeder` runs at startup and makes sure `ADMIN_EMAIL` exists as an `ADMIN`, with `ADMIN_PASSWORD`.
Compose passes both in from `.env` and refuses to start without them; `./mvnw test` and a bare
`spring-boot:run` have neither, and get a warning and no admin rather than a failure to boot.

Seeding is **create-only**. If the address already exists it is promoted, but its password is left exactly
as it is -- so a restart cannot quietly reset a password the admin has since changed, and a stale `.env`
cannot hand the account back to whoever last read that file.

It lives here rather than in `docker/initdb.sql` with the rest of the database setup because the password
has to be BCrypt hashed the way this service does it, and SQL cannot.

## Accounts and passwords

BCrypt via `spring-security-crypto` -- the full security starter would install a filter chain that would
then have to be switched off. Only the hash is stored.

- At least 8 characters, at most 72 **bytes**. BCrypt reads no further than 72, which would make any two
  passwords sharing a 72-byte prefix the same password, so the long ones are rejected rather than truncated
  behind the user's back.
- Emails are lowercased and must be unique. The unique index on `accounts.email` is the real guard, so two
  simultaneous registrations still come back as a 400 rather than a 500.
- A login for an unknown email still runs one hash comparison against a dummy hash, so a missing account
  costs the same time as a wrong password.
- Five failed logins for one email inside 15 minutes lock that email out with a 429, correct password
  included. Per email rather than per IP, because every browser request arrives from the Node proxy and
  would otherwise share one counter -- the trade is that someone who knows an address can lock it out on
  purpose.
- Editing an account always requires the current password, even to change only the name, so a borrowed tab
  cannot quietly take an account over.

## Tokens

An RSA keypair is generated at startup and never written to disk. Tokens are RS256 JWTs carrying `sub`
(the account id), `email`, `name`, `role`, `iat` and `exp`. The public half is published at
`/api/jwks.json` with a `kid`, so a second key can be added later without breaking anything.

The id is the subject because it is the one thing about an account that never changes; a rename or a new
email does not orphan anything that points at it.

A restart mints a new keypair and so invalidates every token in flight. Accounts are untouched. There is no
logout endpoint to pair with any of this: a signed token is good until it expires, so logging out is the
client dropping the token it holds, and `auth.token-ttl` is the real bound.

## Endpoints

| Method | Path | Body / notes |
|---|---|---|
| POST | `/api/accounts` | `{name, email, password}` -> 201 `{id, name, email, role}`, always `USER` |
| POST | `/api/login` | `{email, password}` -> `{token, name, email}`, 401, or 429 once locked out |
| GET | `/api/accounts/me` | the caller's own account |
| PUT | `/api/accounts/me` | `{name, email, currentPassword, newPassword?}` |
| GET | `/api/accounts` | everyone -- admin and moderator only, else 403 |
| PUT | `/api/accounts/{id}/role` | `{role}` -- admin only, else 403 |
| GET | `/api/public/stats` | `{accounts}` -- no token |
| GET | `/api/jwks.json` | the public signing key -- no token |

Everything except the last two needs `Authorization: Bearer <token>`. Every rejection comes back as
`{"error": "..."}`, which `AuthExceptionAdvice` is responsible for.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | `9081` |
| `auth.token-ttl` | `AUTH_TOKEN_TTL` | `30m` |
| `admin.email` | `ADMIN_EMAIL` | empty -- no admin is seeded |
| `admin.password` | `ADMIN_PASSWORD` | empty -- no admin is seeded |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/auth` |
| `spring.datasource.username` / `.password` | `SPRING_DATASOURCE_*` | `auth` / `auth` |

## Running it alone

```bash
docker compose up -d db                  # it still needs a database
./mvnw -pl apps/auth-service spring-boot:run
./mvnw -pl apps/auth-service test        # SQLite backed: needs nothing running
```

Tests: `AccountServiceTests` and `SessionServiceTests` for the rules, `ApiContractTests` for the HTTP
contract the frontend and todo-service are written against -- status codes, the `{error}` shape, the
`Authorization` header, and the seeded admin. Each uses its own SQLite file so re-creating the schema in
one cannot disturb another.
