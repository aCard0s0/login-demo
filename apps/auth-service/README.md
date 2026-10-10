# auth-service

Users, login, roles, and the RS256 keypair every other service verifies against. Port **9081**.

It is the only service that writes to the `auth` database, and the only holder of the private signing key.
Nothing here calls todo-service; the traffic goes the other way, and only for the public key.

## Packages

Split by subject rather than by layer -- one package holds its entity, service, controller and records
together, so a change to one subject stays in one folder.

```
user/      UserService  UserController  CurrentUserResolver  AdminSeeder
  entities/  User  Role  UserRepository  AgentTokenVersion  AgentTokenVersionRepository
  dto/       UserResponse  NewUser  UpdateUser  RoleChange  Suspension
session/   Session  SessionService  SessionController  LoginRequest  LoginResponse
token/     Tokens  JwksController
oauth/     OAuthController   (the flow and the providers are libs/auth-provider)
internal/  AgentTokenController  TokenVersionController   (the /internal endpoints: compose network only)
stats/     StatsController  PublicStats
support/   AuthExceptionAdvice  AttemptWindow  Passwords
```

The dependencies point one way: `token` <- `user` <- {`session`, `oauth`, `internal`} -- the two ways in and
the compose-only corner, none knowing about the others. `Tokens.issue` takes an id, an email, a name and a
role rather than a `User`, which is what keeps `token` free of the user package. `stats` is the
unauthenticated corner and `internal` the never-proxied one, each kept apart so the trust boundary shows up
in the tree rather than in a comment.

A controller takes the caller as a `User` parameter, which `CurrentUserResolver` reads off the
`Authorization` header before the method runs -- the same shape as `Caller` in the other services, except
that this one re-reads the row. Every refusal is a `ResponseStatusException` with the status it means (400,
401, 403, 404, 429), rendered as `{"error": "..."}` by `AuthExceptionAdvice`; there is no catch-all for
`IllegalArgumentException`, which would hand a library's message to the client as a 400.

## Roles

The role lives on the user row, stored as its name (`@Enumerated(EnumType.STRING)`) so reordering the
enum cannot silently re-grade anybody. See the [root README](../../README.md#roles) for the matrix that
spans both services; what *this* service enforces is:

| Endpoint | ADMIN | MODERATOR | AGENT | USER |
|---|---|---|---|---|
| `GET /api/users/me` | own | own | 403 | own |
| `PUT /api/users/me` | own | own | 403 | own |
| `GET /api/users` | everyone | everyone | 403 | 403 |
| `PUT /api/users/{id}/role` | any user | 403 | 403 | 403 |
| `PUT /api/users/{id}/suspended` · `POST /api/users/{id}/revoke` | any user | 403 | 403 | 403 |

Registration always produces a `USER`: `POST /api/users` has no role field to ask with, and
`PUT /api/users/me` cannot change one. `PUT /api/users/{id}/role` is the single door off `USER`.

Two rules worth knowing:

- **An admin cannot demote itself.** The last one doing so would leave nobody able to promote anybody ever
  again, so `UserService.changeRole` refuses it rather than trusting whoever writes the next controller.
- **A change of role revokes every token the user holds**, the same as a suspension. The role rides inside
  the token and todo-, agent- and wallet-service read it from there (see todo-service's README for why), so
  a demotion that left the old token alive would keep its holder an admin everywhere else for up to
  `auth.token-ttl`. The user logs in again and gets the new role; setting the role a user already has
  revokes nothing. This service itself reads the role from the row, so here it would have bitten on the next
  request either way.

An **agent token** -- the `AGENT` role, minted for one of a user's agents -- is 403 on every endpoint here,
`GET /api/users/me` included. Its only way in is `/mcp` through agent-service; let in here it could change
its owner's email and password with a 30-day token. `UserService.byToken` refuses it after checking it is
otherwise valid, so a revoked or expired agent token is still a plain 401 and says nothing more.

An admin changing *another* user's name, email or password is deliberately not implemented:
`PUT /api/users/me` requires the current password even to change only the name, and admin-bypassing that
would be impersonation rather than administration.

### The seeded admin

`AdminSeeder` runs at startup and makes sure `ADMIN_EMAIL` exists as an `ADMIN`, with `ADMIN_PASSWORD`.
Compose passes both in from `.env` and refuses to start without them; `./mvnw test` and a bare
`spring-boot:run` have neither, and get a warning and no admin rather than a failure to boot.

Seeding is **create-only**. If the address already exists it is promoted, but its password is left exactly
as it is -- so a restart cannot quietly reset a password the admin has since changed, and a stale `.env`
cannot hand the user back to whoever last read that file.

It lives here rather than in `docker/initdb.sql` with the rest of the database setup because the password
has to be BCrypt hashed the way this service does it, and SQL cannot.

## Users and passwords

BCrypt via `spring-security-crypto` -- the full security starter would install a filter chain that would
then have to be switched off. Only the hash is stored.

- At least 8 characters, at most 72 **bytes**. BCrypt reads no further than 72, which would make any two
  passwords sharing a 72-byte prefix the same password, so the long ones are rejected rather than truncated
  behind the user's back.
- Names and emails are at most 255 characters, the width of the column. Checked here so Postgres does not
  answer the overflow with a misleading "already registered".
- Emails are lowercased and must be unique. The unique index on `users.email` is the real guard, so two
  simultaneous registrations still come back as a 400 rather than a 500.
- A login for an unknown email still runs one hash comparison against a dummy hash, so a missing user
  costs the same time as a wrong password.
- Five failed logins for one email inside 15 minutes lock that email out with a 429, correct password
  included. Per email rather than per IP, because every browser request arrives from the Node proxy and
  would otherwise share one counter -- the trade is that someone who knows an address can lock it out on
  purpose.
- Registration is capped at 30 attempts per 15 minutes, service-wide, and answers 429 past that. Service-wide
  rather than per client because every request arrives from the Node proxy under one address and the proxy
  sets no `X-Forwarded-For`; the trade is that a run of bots makes honest registrations wait the window out.
- Editing a user always requires the current password, even to change only the name, so a borrowed tab
  cannot quietly take a user over.
- A new password kills every token the user held, the one that asked included: whoever changes a password
  usually suspects someone else has the old one, and that someone's session must not outlive it. The answer
  to `PUT /api/users/me` carries a fresh `token`, which the profile page swaps in rather than being signed out.
- A password longer than 72 bytes is refused at registration and treated as plainly wrong at login, never
  handed to BCrypt (which throws past 72 since Spring Security 7); every check costs one hash either way.
  All of this is `Passwords`, the one place a password is hashed or compared.

## Signing in with a provider

Google, GitHub, Microsoft, Apple, X, LinkedIn and Discord, every one off until a deployment configures it.
The OAuth 2.0 authorization-code flow, written out by hand against `RestClient` in
[libs/auth-provider](../../libs/auth-provider/README.md): `OAuthFlow` builds the consent URL and trades the
code for a verified `Identity`, and each provider is its own jar (`auth-provider-google`, `auth-provider-github`,
`auth-provider-microsoft`, `auth-provider-apple`, `auth-provider-x`, `auth-provider-linkedin`,
`auth-provider-discord`) that this service depends on. Not `spring-boot-starter-oauth2-client`, for the same reason the passwords are
not `spring-boot-starter-security`: it installs a filter chain this service does not have, and switching it
back off is more code than the flow. What stays here is `OAuthController`: the two redirects, the state and
PKCE cookies, and what to do with the identity that comes back -- match it to a user and mint one of our tokens.

```
GET /api/oauth/google/start      302 -> accounts.google.com, with a state cookie planted
   ... consent ...
GET /api/oauth/google/callback   code -> access token -> verified email -> our own JWT
                                 302 -> /login#token=...&name=...
```

The things worth knowing:

- **A user is matched by verified email and nothing else.** That is what makes "continue with Google"
  and "log in with a password" the same user rather than two, and why an unverified address is refused:
  accepting one would let anyone who can claim an address at a provider walk into the user that already
  owns it here. Google states `email_verified`; GitHub says nothing on `/user`, and hides the address
  entirely when the GitHub user keeps it private, so the address always comes from `/user/emails` where the
  `primary` and `verified` flags live.
- **The callback is guarded by a state cookie**, planted on the way out and required to match the `state`
  the provider echoes back. An attacker can make a browser visit the callback but cannot set a cookie on
  this origin, so the two halves cannot be made to agree. `SameSite=Lax`, not `Strict`: the callback is a
  cross-site top-level navigation, and `Strict` would withhold the cookie exactly when it is needed. Apple
  sends the browser back with a POST rather than a GET, which `Lax` would also withhold the cookie from, so
  for Apple the cookies are `SameSite=None; Secure` and the callback takes a POST -- and so Apple only works
  over HTTPS, which Apple requires of the redirect URI anyway.
- **The callback also carries PKCE.** A verifier is planted in a second cookie next to the state, the
  provider is shown its SHA-256 on the way out, and the verifier itself travels only in the back-channel token
  request. A code lifted from the redirect is worthless without the cookie that started it.
- **The token comes back in the URL fragment**, not the query string, so it is never sent to a server,
  written to the proxy's access log, or passed on in a `Referer` header. The login page reads it, stores it
  the same way a password login does, and clears the fragment.
- **A user created this way has no usable password** -- the column holds a hash of a value nobody
  holds, rather than being nullable and making every password path test for it. The consequence is that its
  owner cannot use `/profile`, which asks for a current password. A "set a password" flow is the upgrade;
  it is marked in `UserService` and is not built.
- **Every call to a provider is bounded** at 10 seconds to connect and 10 to answer, so a provider that
  hangs costs one failed sign-in and not a request thread for good.
- **A disabled provider answers 404**, the same as an unknown one. Which providers a deployment configured
  is nobody else's business.

Every provider is off unless a deployment sets `ENABLED` *and* every credential it needs -- half-filled
credentials count as off, so a copied-and-unfinished `.env` draws no button that leads to a provider error
page. `GET /api/oauth/providers` is what the login page reads to know which buttons to draw.

Each provider needs `<oauth.redirect-base-url>/api/oauth/<provider>/callback` registered as its callback
URL, exactly as written, host and port included. Serving the frontend from somewhere else means changing
the property and the registered URI together.

### Getting a client id and secret

Every provider hands out a **client id** (public, and visible to anyone who clicks a button) and a **client
secret** (private, and only ever sent from this service to the provider's token endpoint, never through the
browser) -- except Apple, which hands out a private key instead, see below. The id stays readable on the
app's page forever; the **secret is shown once**, at creation, so copy it straight into `.env`. No provider
will show it again, and the fix for a lost one is to generate a replacement.

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

#### Microsoft

1. [entra.microsoft.com](https://entra.microsoft.com) -> **App registrations** -> **New registration**.
   Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**,
   which is what the `common` endpoint this service uses serves. Redirect URI, platform **Web**:
   `http://localhost:3000/api/oauth/microsoft/callback` (Microsoft allows `http` for `localhost` only).
2. **Certificates & secrets** -> **New client secret**. Copy the **Value**, not the Secret ID.
3. **Token configuration** -> **Add optional claim** -> token type **ID** -> tick **email**, and accept
   turning on the Graph `email` permission. Then **Manifest** -> in `optionalClaims.idToken` add
   `{"name": "xms_edov", "source": null, "essential": false, "additionalProperties": []}`. The portal may
   warn the claim is unrecognised; it is not. Without it, this service refuses every Microsoft sign-in as
   unverified, because the plain `email` claim is whatever a tenant admin typed.
4. The client id is the **Application (client) ID** on the overview page.

#### Apple

Apple is the odd one out: no `localhost`, no `http`, and no secret to copy.

1. [developer.apple.com/account](https://developer.apple.com/account) -> **Identifiers** -> **+** -> **App IDs**,
   with the **Sign in with Apple** capability. This is the parent; it is not the client id.
2. **Identifiers** -> **+** -> **Services IDs**. The identifier you choose (`com.example.login`) is the
   **client id**. Enable **Sign in with Apple** -> **Configure**: the App ID above as primary, your domain
   under **Domains**, and `https://<your-domain>/api/oauth/apple/callback` under **Return URLs**. Apple
   refuses `http` and `localhost`, so this provider only works on a deployed, HTTPS address.
3. **Keys** -> **+** -> tick **Sign in with Apple** -> **Configure** -> the App ID above. Download the `.p8`
   file: Apple shows it **once**. The **Key ID** is on the key's page; the **Team ID** is top right of the
   account page.
4. Put the `.p8` contents in `OAUTH_APPLE_PRIVATE_KEY` on one line, with `\n` for the line breaks (the
   BEGIN/END lines may stay or go). `OAUTH_APPLE_CLIENT_ID` is the Services ID, plus `OAUTH_APPLE_TEAM_ID`
   and `OAUTH_APPLE_KEY_ID`. A key that does not parse counts as off.

Apple hands the name over once, on the very first consent, in a form field this service does not read; an
Apple user is named after their address's local part until they rename themselves on `/profile`. "Hide my
email" users arrive as `@privaterelay.appleid.com` addresses, which are verified and work like any other.

#### X

1. [developer.x.com/en/portal/dashboard](https://developer.x.com/en/portal/dashboard) -> your project ->
   your app -> **Settings** -> **User authentication settings** -> **Set up**.
2. **App permissions**: Read. Tick **Request email from users** -- without it the API never returns the
   address and every sign-in is refused. **Type of App**: Web App. **Callback URI**:
   `http://localhost:3000/api/oauth/x/callback`; **Website URL**: anything.
3. **Keys and tokens** -> **OAuth 2.0 Client ID and Client Secret** -> **Generate**. Both are shown once.

X requires PKCE, which every provider here gets anyway, and reads the client secret only as HTTP Basic on
the token request, which `XProvider` says with `basicClientAuth()`. The email comes from
`/2/users/me?user.fields=confirmed_email` under the `users.email` scope; an account created with a phone
number and no email answers `{}` and is refused.

#### LinkedIn

1. [linkedin.com/developers/apps](https://www.linkedin.com/developers/apps) -> **Create app**. It must be
   attached to a LinkedIn Page you administer.
2. **Products** -> **Sign In with LinkedIn using OpenID Connect** -> **Request access** (instant). This is
   what grants the `openid profile email` scopes; the old `r_emailaddress` product is gone.
3. **Auth** -> **Authorized redirect URLs for your app** -> `http://localhost:3000/api/oauth/linkedin/callback`.
   The client id and **Primary Client Secret** are on the same tab.

#### Discord

1. [discord.com/developers/applications](https://discord.com/developers/applications) -> **New Application**.
2. **OAuth2** -> **Redirects** -> `http://localhost:3000/api/oauth/discord/callback`. The **Client ID** is
   on the same page; **Reset Secret** shows the secret once.

Discord says in `verified` whether it has confirmed the address; an unconfirmed Discord account is refused.

#### Putting them in

```bash
OAUTH_GOOGLE_ENABLED=true
OAUTH_GOOGLE_CLIENT_ID=....apps.googleusercontent.com
OAUTH_GOOGLE_CLIENT_SECRET=...

OAUTH_GITHUB_ENABLED=true
OAUTH_GITHUB_CLIENT_ID=...
OAUTH_GITHUB_CLIENT_SECRET=...

# And likewise OAUTH_MICROSOFT_*, OAUTH_X_*, OAUTH_LINKEDIN_*, OAUTH_DISCORD_*; Apple takes
OAUTH_APPLE_ENABLED=true
OAUTH_APPLE_CLIENT_ID=com.example.login
OAUTH_APPLE_TEAM_ID=ABCDE12345
OAUTH_APPLE_KEY_ID=FGHIJ67890
OAUTH_APPLE_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\nMIGT...\n-----END PRIVATE KEY-----
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
(the user id), `email`, `name`, `role`, `ver` (the user's token version), `iat` and `exp`. An agent
token adds `agent` (the one agent it is pinned to) and `agentVer` (that agent's own token version, see
below). The public half is published at `/api/jwks.json` with a `kid`, so a second key can be added later
without breaking anything.

Two token versions, both kept here because this is where tokens are minted and where the feed the other
services poll is served. The user's `tokenVersion` is bumped by an admin's revoke or suspend and kills
every token the user holds. An agent's `AgentTokenVersion` row is bumped by its owner, through
agent-service and `POST /internal/agent-tokens/{agentId}/revoke`, and kills that one agent's tokens alone.
`/internal/token-versions` publishes both in one map, users by id and agents as `agent:<id>`, so the two
kinds of key cannot collide. The services require `agentVer` on every agent token: one minted before the
claim existed cannot be told from one revoked since, so it is refused.

The id is the subject because it is the one thing about a user that never changes; a rename or a new
email does not orphan anything that points at it.

A restart mints a new keypair and so invalidates every token in flight. Users are untouched. There is no
logout endpoint to pair with any of this: a signed token is good until it expires, so logging out is the
client dropping the token it holds, and `auth.token-ttl` is the real bound.

## Endpoints

| Method | Path | Body / notes |
|---|---|---|
| POST | `/api/users` | `{name, email, password}` -> 201 `{id, name, email, role}`, always `USER`; 429 past the cap |
| POST | `/api/login` | `{email, password}` -> `{token, name, email, role}`, 401, or 429 once locked out |
| GET | `/api/users/me` | the caller themselves; an agent token is 403 |
| PUT | `/api/users/me` | `{name, email, currentPassword, newPassword?}` -> the user plus a fresh `token`; a new password revokes every other; an agent token is 403 |
| GET | `/api/users` | everyone -- admin and moderator only, else 403 |
| PUT | `/api/users/{id}/role` | `{role}` -- admin only, else 403; a change also revokes the user's tokens |
| PUT | `/api/users/{id}/suspended` | `{suspended}` -- admin only; suspending also revokes; not on yourself |
| POST | `/api/users/{id}/revoke` | no body -- admin only; kills every token the user holds |
| GET | `/internal/token-versions` | `{"<userId>": version, "agent:<agentId>": version}` for every revoked user and agent; todo-, agent- and wallet-service poll it |
| POST | `/internal/agent-tokens` | `{userId, agentId}` -> `{token}`: a 30-day `AGENT` token for one agent, stamped with its `agentVer`; agent-service asks, with `X-Internal-Secret`, else 403 |
| POST | `/internal/agent-tokens/{agentId}/revoke` | -> `{version}`: bumps that agent's version, killing every token minted for it; same secret, else 403 |
| GET | `/api/oauth/providers` | `[{key, label}]` -- the configured providers, no token |
| GET | `/api/oauth/{provider}/start` | 302 to consent, or 404 if that provider is off -- no token |
| GET, POST | `/api/oauth/{provider}/callback` | 302 to `/login#token=...&name=...&role=...` or `/login#error=...` -- no token; POST is what Apple sends |
| GET | `/api/public/stats` | `{users}` -- no token |
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
| `auth.internal-secret` | `AUTH_INTERNAL_SECRET` | `dev-internal-secret` -- what `/internal/agent-tokens` demands as `X-Internal-Secret`; blank closes it; compose requires `INTERNAL_SECRET` in `.env` |
| `admin.email` | `ADMIN_EMAIL` | empty -- no admin is seeded |
| `admin.password` | `ADMIN_PASSWORD` | empty -- no admin is seeded |
| `oauth.redirect-base-url` | `OAUTH_REDIRECT_BASE_URL` | `http://localhost:3000` -- the frontend's address |
| `oauth.google.enabled` | `OAUTH_GOOGLE_ENABLED` | `false` |
| `oauth.google.client-id` / `.client-secret` | `OAUTH_GOOGLE_CLIENT_*` | empty -- and empty means off |
| `oauth.github.enabled` | `OAUTH_GITHUB_ENABLED` | `false` |
| `oauth.github.client-id` / `.client-secret` | `OAUTH_GITHUB_CLIENT_*` | empty -- and empty means off |
| `oauth.microsoft.*`, `oauth.x.*`, `oauth.linkedin.*`, `oauth.discord.*` | `OAUTH_<PROVIDER>_ENABLED`, `_CLIENT_ID`, `_CLIENT_SECRET` | `false`, empty, empty |
| `oauth.apple.enabled` | `OAUTH_APPLE_ENABLED` | `false` |
| `oauth.apple.client-id` / `.team-id` / `.key-id` / `.private-key` | `OAUTH_APPLE_CLIENT_ID`, `_TEAM_ID`, `_KEY_ID`, `_PRIVATE_KEY` | empty -- all four needed, and the key must parse |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/auth` |
| `spring.datasource.username` / `.password` | `SPRING_DATASOURCE_*` | `auth` / `auth` |

## Running it alone

```bash
docker compose up -d db                  # it still needs a database
./mvnw -pl apps/auth-service spring-boot:run
./mvnw -pl apps/auth-service test        # SQLite backed: needs nothing running
```

Tests, each on its own SQLite file so re-creating the schema in one cannot disturb another:

- `UserServiceTests`, `SessionServiceTests`, `AttemptWindowTests`, `AuthExceptionAdviceTests`, `AdminSeederTests`
  for the rules.
- `ApiContractTests` for the HTTP contract the frontend and todo-service are written against -- status codes,
  the `{error}` shape, the `Authorization` header, the seeded admin, agent tokens, and a password change
  handing back a fresh token. `RegistrationCapTests` for the service-wide 429, in a context of its own since
  filling the window would lock every other test out.
- `EndpointAuthSweepTests` is the fail-closed guard: a controller authenticates by declaring a `User`
  parameter, so a new endpoint that forgets it would be public and nothing at runtime would say so. The
  sweep walks every mapping and requires each to be on its list of deliberately public endpoints, guarded by
  the internal secret, or answering 401 without a token -- over HTTP, not by the look of its signature.
- `OAuthTests` for the half of the provider flow that needs no provider: which buttons a deployment offers,
  where the browser is sent, and what a callback that did not start here gets. `OAuthCallbackIntegrationTests`
  for the other half, against a throwaway HTTP server standing in for the provider: the code exchanged with
  the verifier the browser kept, the user found or made, a suspended one refused, the provider's error text
  kept out of the browser, the cookies spent, and the form-POST callback Apple sends.
- `RevocationIntegrationTests` for revocation as todo-, agent- and wallet-service see it, through
  `libs/auth-client` over a real port.
