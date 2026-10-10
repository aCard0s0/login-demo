# auth-provider

Signing in with an external provider. The OAuth 2.0 authorization-code flow with PKCE lives once, in `core`;
each provider is its own plain jar, so a service depends on exactly the ones it offers and nothing else.

```
core/       com.demo.auth.provider            OAuthFlow          consent URL, code -> tokens -> Identity, timeouts
                                              OAuthProvider      the contract: endpoints, scope, credentials, identity()
                                              OAuthProperties    oauth.redirect-base-url, oauth.callback-path
                                              Identity           a verified email and a display name
google/     com.demo.auth.provider.google     GoogleProvider     oauth.google.*     userinfo, email_verified
github/     com.demo.auth.provider.github     GitHubProvider     oauth.github.*     /user + /user/emails, primary+verified
microsoft/  com.demo.auth.provider.microsoft  MicrosoftProvider  oauth.microsoft.*  ID token, xms_edov
apple/      com.demo.auth.provider.apple      AppleProvider      oauth.apple.*      ID token, email_verified; signed client secret; form_post
x/          com.demo.auth.provider.x          XProvider          oauth.x.*          /2/users/me confirmed_email; Basic client auth
linkedin/   com.demo.auth.provider.linkedin   LinkedInProvider   oauth.linkedin.*   OpenID userinfo, email_verified
discord/    com.demo.auth.provider.discord    DiscordProvider    oauth.discord.*    /users/@me, verified
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
already owns it here. Each provider spells "verified" its own way:

| Provider | Where the address comes from | What counts as verified |
|---|---|---|
| Google | OpenID userinfo | `email_verified` |
| GitHub | `/user/emails` (`/user` hides a private address and says nothing about verification) | the entry flagged both `primary` and `verified` |
| Microsoft | the ID token | `xms_edov` -- the plain `email` claim is whatever a tenant admin typed (the "nOAuth" takeover), so the app registration must add this optional claim or every sign-in is refused |
| Apple | the ID token (there is no userinfo endpoint) | `email_verified`; a private relay address still counts |
| X | `/2/users/me?user.fields=confirmed_email` | the field being there at all -- it needs the `users.email` scope and the app's "Request email from users" setting |
| LinkedIn | OpenID userinfo | `email_verified` |
| Discord | `/users/@me` | `verified` |

Three providers bend the flow, and the contract has a method for each bend so `core` stays one flow:

- **`identity(RestClient, Map)`** -- the whole token-endpoint answer rather than just the access token, for a
  provider whose identity is in the `id_token` (Apple, Microsoft). `idTokenClaims` checks the audience is this
  client and the token has not expired; the signature is not checked, which OpenID Connect Core 3.1.3.7 allows
  for a token received straight from the token endpoint over TLS.
- **`basicClientAuth()`** -- the client secret as HTTP Basic on the token request rather than a form field,
  which is the only way X reads it. Never both: RFC 6749 forbids it.
- **`formPost()`** -- the provider sends the browser back with a POST (`response_mode=form_post`), which Apple
  insists on once the email is asked for. The service's callback takes a POST too, and plants its cookies
  `SameSite=None; Secure` for such a provider, because a cross-site POST carries no `Lax` cookie. `None` needs
  `Secure`, so such a provider only works over HTTPS -- which Apple demands of the redirect URI anyway.

Apple also has no client secret to copy: it hands out a `.p8` private key, and the secret is a JWT signed with
it per request, so `AppleProvider` binds `team-id`, `key-id` and `private-key` in place of `client-secret`
and mints the secret in `getClientSecret()`.

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

`OAuthFlowTests` checks PKCE against RFC 7636's own vector, that a half-configured provider is not offered,
and that a form-post provider is asked for one. `OAuthFlowIntegrationTests` runs the back channel over real
HTTP against a throwaway server: the form a provider sees, the secret as a field or as Basic, and an
`id_token` provider's audience and expiry checks. Each provider has a unit test for its identity rule -- which
address is taken and which is refused -- and an integration test that scans and binds it the way a service
does and answers its API (or mints its ID token) the way the provider really does. auth-service's
`OAuthTests` and `OAuthCallbackIntegrationTests` walk the redirects and the callback over MockMvc.
