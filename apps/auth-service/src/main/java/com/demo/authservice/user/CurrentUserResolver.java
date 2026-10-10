package com.demo.authservice.user;

import com.demo.authservice.user.entities.User;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Lets a controller method take a {@link User} parameter: the user behind the {@code Authorization} header,
 * re-read from the database, or 401 -- and 403 for an agent token, which {@link UserService#byToken} refuses.
 *
 * <p>The same shape as {@code CallerResolver} in the other services, which resolve a {@code Caller} from the
 * token's claims. This one cannot: it is the issuer, and it reads the role and the suspension off the row so
 * an admin's change bites on the next request rather than at the next login.
 *
 * <p>The token travels in the header rather than a query parameter so it stays out of access logs and
 * Referer headers. There is no logout endpoint to pair with this: a signed token is good until it expires, so
 * logging out is the client dropping the token it holds.
 */
@Component
public class CurrentUserResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final UserService users;

    public CurrentUserResolver(UserService users) {
        this.users = users;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == User.class;
    }

    @Override
    public User resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                WebDataBinderFactory binders) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer ", "");
        return users.byToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
    }
}
