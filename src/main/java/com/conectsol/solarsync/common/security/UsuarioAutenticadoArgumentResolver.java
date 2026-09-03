package com.conectsol.solarsync.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolve {@code @Autenticado UsuarioAutenticado} a partir do {@link JwtAuthenticationToken}.
 * <p>
 * Deliberadamente um resolver, e não {@code @AuthenticationPrincipal} com um token
 * customizado: para o principal ser um {@code UsuarioAutenticado} seria preciso substituir o
 * {@code JwtAuthenticationToken}, e nos testes o
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} constrói esse token direto, sem passar
 * pelo converter — o principal chegaria como {@code Jwt} e todo controller quebraria em teste.
 * Lendo o {@code Jwt}, o comportamento é idêntico em produção e em teste.
 */
@Component
public class UsuarioAutenticadoArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parametro) {
        return parametro.hasParameterAnnotation(Autenticado.class)
                && UsuarioAutenticado.class.isAssignableFrom(parametro.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parametro, ModelAndViewContainer modelo,
            NativeWebRequest requisicao, WebDataBinderFactory fabricaDeBinder) {

        if (SecurityContextHolder.getContext()
                .getAuthentication() instanceof JwtAuthenticationToken token) {
            return UsuarioAutenticado.de(token.getToken());
        }
        throw new AuthenticationCredentialsNotFoundException("Requisição sem token JWT");
    }
}
