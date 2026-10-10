# auth-provider

Signing in with an external provider. The OAuth 2.0 authorization-code flow with PKCE lives once, in `core`;
each provider is its own plain jar, so a service depends on exactly the ones it offers and nothing else.

```
core/     com.demo.auth.provider          OAuthFlow        consent URL, code -> token -> Identity, timeouts
                                          OAuthProvider    the contract: endpoints, scope, credentials, identity()
                                          OAuthProperties  oauth.redirect-base-url, oauth.callback-path
                                          Identity         a verified email and a display name
google/   com.demo.auth.provider.google   GoogleProvider   oauth.google.*
github/   com.demo.auth.provider.github   GitHubProvider   oauth.github.*
```

Hand-rolled rather than `spring-boot-starter-oauth2-client`, because that starter brings the security filter
chain these services deliberately do not have. Nothing here needs a filter: the browser arrives at two
ordinary endpoints, and those endpoints stay in the service, because what happens after sign-in -- match a
user, mint a token, redirect somewhere -- is the service's business. auth-service's `OAuthController` is the
reference.

## Using it

Depend on the provider jars you offer and scan the package:

```xml
<dependency>
    <groupId>com.demo</groupId>
    <artifactId>auth-provider-google</artifactId>
    <version>${project.version}</version>
</dependency>
```

```java
@SpringBootApplication(scanBasePackages = {"com.demo.myservice", "com.demo.auth.provider"})
```

Then `OAuthFlow` is a bean with every provider on the classpath, and `enabled()` is whichever of them a
deployment configured:

| Property | Default |
|---|---|
| `oauth.redirect-base-url` | `http://localhost:3000` -- the address the browser uses, so the one the provider redirects to |
| `oauth.callback-path` | `/api/oauth/{provider}/callback` -- under the base; `{provider}` is the key |
| `oauth.<key>.enabled` | `false` |
| `oauth.<key>.client-id` / `.client-secret` | empty -- and empty means off, even when enabled |

Each provider needs `<base><callback-path>` registered with it as the callback URL, exactly as written, host
and port included. The step-by-step for getting a client id and secret from Google and GitHub is in
[auth-service's README](../../apps/auth-service/README.md#getting-a-client-id-and-secret).

## The contract

A user is matched to a provider identity by **verified** email and nothing else. That is what makes "log in
with Google" and "log in with a password" the same user, and why `OAuthProvider.identity` must refuse an
unverified address: accepting one would let anyone who can claim it at the provider walk into the user that
already owns it here. Google states `email_verified`; GitHub says nothing on `/user` and hides the address
when it is private, so `GitHubProvider` reads `/user/emails` and takes the one flagged both `primary` and
`verified`.

`identity` throws `IllegalStateException` with a message that is safe to show the person signing in. Anything
else a provider call throws (a 4xx, a timeout) is a `RestClientException`, whose text can name internals: the
service logs it and shows a flat message.

## Adding a provider

One module, one class, no edits to `core`:

```java
@Component
@ConfigurationProperties("oauth.myidp")
public class MyIdpProvider extends OAuthProvider {
    public MyIdpProvider() { super("myidp", "MyIdP", AUTHORIZE_URI, TOKEN_URI, "openid email"); }

    @Override
    public Identity identity(RestClient http, String accessToken) {
        Map<?, ?> me = get(http, USERINFO_URI, accessToken, Map.class);
        // refuse unless the provider says the address is verified
        return new Identity(string(me, "email"), string(me, "name"));
    }
}
```

Bind it to `oauth.<key>` so `OAUTH_MYIDP_CLIENT_ID` reaches it by Spring's relaxed rules, the same way the
two here do.

## Tests

`OAuthFlowTests` checks PKCE against RFC 7636's own vector and that a half-configured provider is not
offered. Each provider has one test for the identity rule: which address is taken and which is refused. The
code-for-token exchange is not covered, because faking it would test the fake; auth-service's `OAuthTests`
walks the redirects over MockMvc.
