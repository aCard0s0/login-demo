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
session/  Session  SessionService  SessionController  LoginRequest  LoginResponse
token/    Tokens  JwksController
oauth/    OAuthProvider  OAuthProperties  OAuthService  OAuthController
stats/    StatsController  PublicStats
support/  AuthExceptionAdvice  AttemptWindow  TooManyAttemptsException
```

The dependencies point one way: `token` <- `account` <- {`session`, `oauth`} -- the two ways in, neither
knowing about the other. `Tokens.issue` takes an id, an email, a name and a role rather than an `Account`,
which is what keeps `token` free of the account package. `stats` is the unauthenticated corner, kept apart
so the trust boundary shows up in the tree.

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
| `PUT /api/accounts/{id}/suspended` · `POST /api/accounts/{id}/revoke` | any account | 403 | 403 | 403 |

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
- Names and emails are at most 255 characters, the width of the column. Checked here so Postgres does not
  answer the overflow with a misleading "already registered".
- Emails are lowercased and must be unique. The unique index on `accounts.email` is the real guard, so two
  simultaneous registrations still come back as a 400 rather than a 500.
- A login for an unknown email still runs one hash comparison against a dummy hash, so a missing account
  costs the same time as a wrong password.
- Five failed logins for one email inside 15 minutes lock that email out with a 429, correct password
  included. Per email rather than per IP, because every browser request arrives from the Node proxy and
  would otherwise share one counter -- the trade is that someone who knows an address can lock it out on
  purpose.
- Registration is capped at 30 attempts per 15 minutes, service-wide, and answers 429 past that. Service-wide
  rather than per client because every request arrives from the Node proxy under one address and the proxy
  sets no `X-Forwarded-For`; the trade is that a run of bots makes honest registrations wait the window out.
- Editing an account always requires the current password, even to change only the name, so a borrowed tab
  cannot quietly take an account over.

## Signing in with Google or GitHub

The OAuth 2.0 authorization-code flow, written out by hand in `oauth/` against `RestClient`. Not
`spring-boot-starter-oauth2-client`, for the same reason the passwords are not `spring-boot-starter-security`:
it installs a filter chain this service does not have, and switching it back off is more code than the flow.
Nothing here needs a filter -- the browser arrives at two ordinary endpoints.

```
GET /api/oauth/google/start      302 -> accounts.google.com, with a state cookie planted
   ... consent ...
GET /api/oauth/google/callback   code -> access token -> verified email -> our own JWT
                                 302 -> /login#token=...&name=...
```

The things worth knowing:

- **An account is matched by verified email and nothing else.** That is what makes "continue with Google"
  and "log in with a password" the same account rather than two, and why an unverified address is refused:
  accepting one would let anyone who can claim an address at a provider walk into the account that already
  owns it here. Google states `email_verified`; GitHub says nothing on `/user`, and hides the address
  entirely when the account keeps it private, so the address always comes from `/user/emails` where the
  `primary` and `verified` flags live.
- **The callback is guarded by a state cookie**, planted on the way out and required to match the `state`
  the provider echoes back. An attacker can make a browser visit the callback but cannot set a cookie on
  this origin, so the two halves cannot be made to agree. `SameSite=Lax`, not `Strict`: the callback is a
  cross-site top-level navigation, and `Strict` would withhold the cookie exactly when it is needed.
- **The callback also carries PKCE.** A verifier is planted in a second cookie next to the state, the
  provider is shown its SHA-256 on the way out, and the verifier itself travels only in the back-channel token
  request. A code lifted from the redirect is worthless without the cookie that started it.
- **The token comes back in the URL fragment**, not the query string, so it is never sent to a server,
  written to the proxy's access log, or passed on in a `Referer` header. The login page reads it, stores it
  the same way a password login does, and clears the fragment.
- **An account created this way has no usable password** -- the column holds a hash of a value nobody
  holds, rather than being nullable and making every password path test for it. The consequence is that its
  owner cannot use `/account`, which asks for a current password. A "set a password" flow is the upgrade;
  it is marked in `AccountService` and is not built.
- **Every call to a provider is bounded** at 10 seconds to connect and 10 to answer, so a provider that
  hangs costs one failed sign-in and not a request thread for good.
- **A disabled provider answers 404**, the same as an unknown one. Which providers a deployment configured
  is nobody else's business.

Both providers are off unless a deployment sets `ENABLED` *and* both credentials -- half-filled credentials
count as off, so a copied-and-unfinished `.env` draws no button that leads to a provider error page.
`GET /api/oauth/providers` is what the login page reads to know which buttons to draw.

Each provider needs `<oauth.redirect-base-url>/api/oauth/<provider>/callback` registered as its callback
URL, exactly as written, host and port included. Serving the frontend from somewhere else means changing
the property and the registered URI together.

### Getting a client id and secret

Both providers hand out a **client id** (public, and visible to anyone who clicks a button) and a **client
secret** (private, and only ever sent from this service to the provider's token endpoint, never through the
browser). The id stays readable on the app's page forever; the **secret is shown once**, at creation, so
copy it straight into `.env`. Neither provider will show it again, and the fix for a lost one is to
generate a replacement.

The URLs below assume the default `http://localhost:3000`. On any other address, substitute it in the
callback URL *and* in `OAUTH_REDIRECT_BASE_URL`, together: a provider rejects a `redirect_uri` it was not
given in advance, character for character.

#### Google

1. [console.cloud.google.com](https://console.cloud.google.com) -> pick a project, or create one. A
   throwaway project is fine; the OAuth client belongs to the project, not to your account.
2. **Google Auth Platform** -> **Branding**. Fill in an app name and a user support email. This registers
   the app, and the console will not let you create a client until it is done.
3. **Google Auth Platform** -> **Audience**. Choose **External**, then add your own Google address under
   **Test users**. While the app is in *Testing*, only the addresses listed there can sign in at all.
4. **Google Auth Platform** -> **Clients** -> **Create client** -> application type **Web application**.
5. Under **Authorized redirect URIs**, add exactly:

   ```
   http://localhost:3000/api/oauth/google/callback
   ```

   Leave **Authorized JavaScript origins** empty. That field is for flows where the browser itself calls
   Google; here the code is exchanged from this service, server to server. Google requires HTTPS for both
   fields, with `localhost` the one exception -- which is why the demo works without a certificate.
6. **Create**. The client id and secret appear immediately; afterwards only the last four characters of the
   secret are ever shown again.

Two consequences of *Testing* worth knowing, both from Google's own rules: a test user normally sees an
"unverified app" warning and their grant expires after seven days -- but an app asking only for basic
profile information is exempt from both, and `openid email profile` is exactly that. So this demo needs no
verification review and no weekly re-consent. Asking for anything more would change that.

#### GitHub

1. Profile picture -> **Settings** -> **Developer settings** -> **OAuth apps** -> **New OAuth App**.
   (For an app owned by an organization: **Your organizations** -> **Settings** -> **Developer settings**.)
2. Fill in:

   | Field | Value |
   |---|---|
   | Application name | anything -- it is what the consent screen shows |
   | Homepage URL | `http://localhost:3000` |
   | Authorization callback URL | `http://localhost:3000/api/oauth/github/callback` |

3. **Register application**. The client id is on the page that follows.
4. Next to **Client secrets**, click **Generate a new client secret**, and copy it before leaving the page.

GitHub takes up to 10 callback URLs on one app, so one app can cover localhost and a deployed address
rather than needing two. There is no test-user list and no review for this scope: the app works for anyone
as soon as it is registered.

#### Putting them in

```bash
OAUTH_GOOGLE_ENABLED=true
OAUTH_GOOGLE_CLIENT_ID=....apps.googleusercontent.com
OAUTH_GOOGLE_CLIENT_SECRET=...

OAUTH_GITHUB_ENABLED=true
OAUTH_GITHUB_CLIENT_ID=...
OAUTH_GITHUB_CLIENT_SECRET=...
```

Then `./login-demo start auth-service` -- compose re-reads `.env`, and `GET /api/oauth/providers` is the
quickest way to see whether a provider took. An empty list means a credential is missing or blank, since
enabled-but-unconfigured counts as off.

`.env` is gitignored and `.env.example` is the committed stand-in, so keep real secrets out of the latter.

Rotating one needs no downtime, because both providers let a second secret exist alongside the first: on
Google, **Add Secret** on the client's page (two is the maximum, and an old one must be disabled and then
deleted before a third can be made); on GitHub, **Generate a new client secret**. Add the new one, put it
in `.env`, restart, and only then disable and delete the old one -- in that order, so a mistake is a
restart rather than an outage.

## Tokens

An RSA keypair is generated at startup and never written to disk. Tokens are RS256 JWTs carrying `sub`
(the account id), `email`, `name`, `role`, `ver` (the account's token version), `iat` and `exp`. The public half is published at
`/api/jwks.json` with a `kid`, so a second key can be added later without breaking anything.

The id is the subject because it is the one thing about an account that never changes; a rename or a new
email does not orphan anything that points at it.

A restart mints a new keypair and so invalidates every token in flight. Accounts are untouched. There is no
logout endpoint to pair with any of this: a signed token is good until it expires, so logging out is the
client dropping the token it holds, and `auth.token-ttl` is the real bound.

## Endpoints

| Method | Path | Body / notes |
|---|---|---|
| POST | `/api/accounts` | `{name, email, password}` -> 201 `{id, name, email, role}`, always `USER`; 429 past the cap |
| POST | `/api/login` | `{email, password}` -> `{token, name, email, role}`, 401, or 429 once locked out |
| GET | `/api/accounts/me` | the caller's own account |
| PUT | `/api/accounts/me` | `{name, email, currentPassword, newPassword?}` |
| GET | `/api/accounts` | everyone -- admin and moderator only, else 403 |
| PUT | `/api/accounts/{id}/role` | `{role}` -- admin only, else 403 |
| PUT | `/api/accounts/{id}/suspended` | `{suspended}` -- admin only; suspending also revokes; not on yourself |
| POST | `/api/accounts/{id}/revoke` | no body -- admin only; kills every token the account holds |
| GET | `/internal/token-versions` | `{accountId: version}` for every revoked account; todo-service and agent-service poll it |
| POST | `/internal/agent-tokens` | `{accountId, agentId}` -> `{token}`: a 30-day `AGENT` token for one agent; agent-service asks |
| GET | `/api/oauth/providers` | `[{key, label}]` -- the configured providers, no token |
| GET | `/api/oauth/{provider}/start` | 302 to consent, or 404 if that provider is off -- no token |
| GET | `/api/oauth/{provider}/callback` | 302 to `/login#token=...&name=...&role=...` or `/login#error=...` -- no token |
| GET | `/api/public/stats` | `{accounts}` -- no token |
| GET | `/api/jwks.json` | the public signing key -- no token |

Everything except the bottom five needs `Authorization: Bearer <token>`. Every rejection comes back as
`{"error": "..."}`, Spring's own included -- a body that will not parse, an unknown role, a non-numeric id, an
unknown path -- which `AuthExceptionAdvice` is responsible for.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | `9081` |
| `auth.token-ttl` | `AUTH_TOKEN_TTL` | `30m` |
| `auth.agent-token-ttl` | `AUTH_AGENT_TOKEN_TTL` | `30d` -- the tokens `/internal/agent-tokens` mints |
| `admin.email` | `ADMIN_EMAIL` | empty -- no admin is seeded |
| `admin.password` | `ADMIN_PASSWORD` | empty -- no admin is seeded |
| `oauth.redirect-base-url` | `OAUTH_REDIRECT_BASE_URL` | `http://localhost:3000` -- the frontend's address |
| `oauth.google.enabled` | `OAUTH_GOOGLE_ENABLED` | `false` |
| `oauth.google.client-id` / `.client-secret` | `OAUTH_GOOGLE_CLIENT_*` | empty -- and empty means off |
| `oauth.github.enabled` | `OAUTH_GITHUB_ENABLED` | `false` |
| `oauth.github.client-id` / `.client-secret` | `OAUTH_GITHUB_CLIENT_*` | empty -- and empty means off |
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
`Authorization` header, and the seeded admin. `OAuthTests` covers the half of the provider flow that needs
no provider: which buttons a deployment offers, where the browser is sent, and what a callback that did not
start here gets. The code-for-token exchange is not covered, because faking it would test the fake. Each
uses its own SQLite file so re-creating the schema in one cannot disturb another.
