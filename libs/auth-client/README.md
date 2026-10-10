# auth-client

auth-service's client half: the one copy of how a service checks a caller's token without asking
auth-service. A plain jar, not a service: no port, no database, no main class. todo-service, agent-service
and account-service depend on it; auth-service does not, because it is the side that mints.

```
com.demo.auth.client   JwtVerifier   the Authorization header -> a Caller, or 401
                       Revocations   the /internal/token-versions feed, polled at most every 10s
                       Caller        account id, role, and the agent an agent token is pinned to
                       CallerResolver  a `Caller` controller parameter: the user, 401, or 403 for an agent
```

`JwtVerifier` fetches auth-service's JWKS once, caches it, and verifies RS256 only -- a token asking for
`none` or a symmetric algorithm is refused before its signature is looked at. `sub` and `exp` are required.
A token whose `ver` is older than the account's last revocation is refused. An `AGENT` token must name its
agent in the `agent` claim, or it is refused rather than read as "every agent of the owner", and must carry
`agentVer` at or above that agent's own last revocation -- a token with no `agentVer` at all is refused,
because one minted before the claim existed cannot be told from one revoked since.

`Revocations` reads one map: accounts by id, agents as `agent:<id>`, so the two cannot collide. It **fails
open**: if auth-service cannot be reached the last list stands, so a revocation made while it is down bites
here once it is back. Failing closed would take every service down with it.

`Caller` answers the questions the services ask -- `isAgent`, `readsEveryone`, `writesEveryone`,
`mayActAs`, `describe` -- and every one of them says "no" for a role it does not know.

`CallerResolver` lets a controller method take `Caller caller` instead of reading the header itself. It
refuses an agent token with 403: an agent's only way in is `/mcp` through agent-service, where its owner's
READ/WRITE setting and activity log apply, so every REST endpoint refuses one without having to remember to.
The MCP servers are not controllers; they call `JwtVerifier` directly.

## Using it

The beans are `@Component`s under `com.demo.auth.client`, outside each service's own package, so the service's
`@SpringBootApplication` lists both packages in `scanBasePackages`. Two properties:

| Property | Environment | Default |
|---|---|---|
| `auth.jwks-uri` | `AUTH_JWKS_URI` | `http://localhost:9081/api/jwks.json` |
| `auth.token-versions-uri` | `AUTH_TOKEN_VERSIONS_URI` | `http://localhost:9081/internal/token-versions` |

## Tests

`./mvnw -pl libs/auth-client test`. `JwtVerifierTests` runs against a real throwaway JWKS server rather than a
mock: the advertised key and nothing else, expiry, the revocation feed for accounts and for one agent alone,
and the agent claims.
