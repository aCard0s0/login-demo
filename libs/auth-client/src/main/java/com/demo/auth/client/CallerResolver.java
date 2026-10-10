package com.demo.auth.client;

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
 * Lets a controller method take a {@link Caller} parameter: the user behind the {@code Authorization} header,
 * or 401 -- and 403 for an agent token.
 *
 * <p>An agent's only way in is {@code /mcp} through agent-service, where its owner's READ/WRITE setting and
 * activity log apply; on a REST API it would skip both, and a READ agent could write with its 30-day token.
 * Refusing it here rather than in each controller means a new endpoint cannot forget to. The MCP servers
 * resolve their caller with {@link JwtVerifier} directly, since they are not controllers.
 */
@Component
public class CallerResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final JwtVerifier jwt;

    public CallerResolver(JwtVerifier jwt) {
        this.jwt = jwt;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        Caller caller = jwt.callerOf(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (caller.isAgent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "an agent token can only connect to /mcp");
        }
        return caller;
    }
}
