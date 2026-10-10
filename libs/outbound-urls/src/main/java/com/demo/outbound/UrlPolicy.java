package com.demo.outbound;

import com.demo.web.errors.Bad;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which URLs a service may be pointed at by something a user typed. A service that POSTs to such a URL does so
 * from inside the deployment's network, where the database and {@code /internal} endpoints live, so a user
 * typing {@code http://auth-service:9081/internal/...} would be a request forged with the service's own standing.
 *
 * <p>Two kinds of URL pass. The deployment's own servers, supplied by {@code trusted}, are accepted exactly as
 * written: they are private addresses by design. Anything else must be http(s), name a host with a dot in it
 * (a bare name is a compose service, or localhost), and resolve only to public addresses: no loopback,
 * private, link-local (where 169.254.169.254, the cloud metadata address, lives), carrier-grade NAT,
 * unique-local or multicast address. An IPv4-mapped IPv6 literal is judged as the IPv4 address it wraps; a
 * name that does not resolve is refused, not deferred.
 *
 * <p>Checked twice: {@link #clean} when the URL is saved, and {@link #checkBeforeConnect} right before every
 * connection against what the name resolves to at that moment, so a name that was public when saved and points
 * somewhere private since (DNS rebinding) is refused too. Redirects are the caller's to refuse: a public server
 * answering 302 to a private address would be this check, undone.
 */
// ponytail: the connect-time check resolves the name and then lets the HTTP client resolve it again. The JVM
// caches a positive lookup for 30s, so the two agree in practice; pinning the connection to the checked address
// needs a custom resolver on the client and is the upgrade.
public class UrlPolicy {

    /** A name to its addresses: a seam for tests, which must not depend on real DNS. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Supplier<Set<String>> trusted;

    private final Resolver resolver;

    /** {@code trusted} is read on every check, so it may follow configuration that changes after startup. */
    public UrlPolicy(Supplier<Set<String>> trusted) {
        this(trusted, InetAddress::getAllByName);
    }

    public UrlPolicy(Supplier<Set<String>> trusted, Resolver resolver) {
        this.trusted = trusted;
        this.resolver = resolver;
    }

    /** The URL as it may be saved, or 400 with the reason. */
    public String clean(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip());
        } catch (IllegalArgumentException e) {
            throw Bad.request("url must be http(s)://host[:port]/path");
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw Bad.request("url must be http(s)://host[:port]/path");
        }
        String clean = uri.toString();
        if (!trusted(clean)) {
            String why = refused(uri.getHost());
            if (why != null) {
                throw Bad.request("url is refused: " + why);
            }
        }
        return clean;
    }

    /** Right before connecting: the same rule, against what the name resolves to now. Throws with the reason. */
    public void checkBeforeConnect(String url) {
        if (trusted(url)) {
            return;
        }
        String why = refused(URI.create(url).getHost());
        if (why != null) {
            throw new IllegalStateException("refused: " + why);
        }
    }

    /** One of the deployment's own servers, exactly as configured. */
    public boolean trusted(String url) {
        return trusted.get().contains(url);
    }

    /** Why this host may not be reached, or null if every address it resolves to is public. */
    private String refused(String host) {
        // URI keeps the brackets on an IPv6 literal; InetAddress does not want them.
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean literal = bare.contains(":") || bare.matches("[0-9.]+");
        if (!literal && !bare.contains(".")) {
            return "'" + host + "' is a bare name, which is a compose service or localhost";
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(bare);
        } catch (UnknownHostException | IllegalArgumentException e) {
            return "'" + host + "' does not resolve";
        }
        for (InetAddress address : addresses) {
            if (notPublic(address)) {
                return "'" + host + "' resolves to " + address.getHostAddress()
                        + ", a loopback, private, link-local, carrier-grade NAT or multicast address";
            }
        }
        return null;
    }

    /** Loopback, any-local, link-local (169.254/16, fe80::/10), site-local (10/8, 172.16/12, 192.168/16), multicast, CGNAT (100.64/10), ULA (fc00::/7). */
    public static boolean notPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();   // an IPv4-mapped IPv6 address has already been unwrapped to four bytes
        if (b.length == 4) {
            return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        }
        return (b[0] & 0xfe) == 0xfc;
    }
}
