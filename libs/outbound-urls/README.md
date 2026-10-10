# outbound-urls

Which URLs a service may be pointed at by something a user typed. A plain jar: no port, no database, no
main class. agent-service depends on it for the MCP servers an owner attaches; anything that will POST to a
user-supplied URL -- a webhook, a callback -- is the next consumer.

```
com.demo.outbound   UrlPolicy   clean(url) on save: 400 with the reason, or the URL as it may be stored
                                checkBeforeConnect(url) right before connecting: throws if the name now points somewhere private
                                trusted(url) one of the deployment's own servers, exactly as written
                    UrlPolicy.Resolver   a name to its addresses; tests hand in a table instead of DNS
```

## The rule

A service calling a user's URL does so from inside the deployment's network, where the database and the
`/internal` endpoints live. So:

- **The deployment's own servers pass by name**, from the `Supplier<Set<String>>` handed to the policy,
  compared exactly as written (a different port or path on the same host is not the trusted server). The set
  is read on every check, so it may follow configuration that is only known after startup.
- **Any other URL** must be `http(s)`, name a host with a dot in it -- a bare name is a compose service, or
  `localhost` -- and resolve **only** to public addresses: no loopback, any-local, private (10/8, 172.16/12,
  192.168/16), link-local (169.254/16, where the cloud metadata address lives, and fe80::/10), carrier-grade
  NAT (100.64/10), unique-local (fc00::/7) or multicast address. An IPv4-mapped IPv6 literal is judged as the
  IPv4 address it wraps; a name that does not resolve is refused, not deferred.
- **Checked twice.** `clean` on save, so nothing private is ever stored; `checkBeforeConnect` right before
  every connection, against what the name resolves to at that moment, so DNS rebinding is refused too.

What the policy does not do: follow or refuse redirects. The HTTP client the caller connects with must not
follow them, or a public server answering 302 to a private address undoes the check.

## Limitation, on purpose

The connect-time check resolves the name, then the HTTP client resolves it again. The JVM caches a positive
lookup for 30 seconds, so the two agree in practice; pinning the connection to the checked address needs a
custom resolver on the client.

## Tests

`UrlPolicyTests`: every refused address category, exact trust, a name that moves to a private address
between save and connect, and a trusted list that changes after construction -- all against a resolver
table rather than DNS.
