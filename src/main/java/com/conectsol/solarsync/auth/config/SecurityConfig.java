package com.conectsol.solarsync.auth.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.conectsol.solarsync.auth.jwt.TokenService;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter conversorDeAutenticacao,
            AuthenticationEntryPoint pontoDeEntrada,
            AccessDeniedHandler tratadorDeAcessoNegado) throws Exception {

        return http
                // API com bearer token e sem cookie de sessão: não há o que um CSRF explore
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sessao -> sessao
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rotas -> rotas
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/refresh").permitAll()
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(conversorDeAutenticacao)))
                .exceptionHandling(tratamento -> tratamento
                        .authenticationEntryPoint(pontoDeEntrada)
                        .accessDeniedHandler(tratadorDeAcessoNegado))
                .build();
    }

    /**
     * Sem esta reconfiguração, o converter de fábrica lê o claim {@code scope} e prefixa
     * {@code SCOPE_} — e então <b>todo</b> {@code hasRole(...)} passa a negar em silêncio.
     * O {@code PendenciaControllerRbacTest} existe para pegar exatamente essa regressão.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.CLAIM_PAPEIS);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(authorities);
        conversor.setPrincipalClaimName(TokenService.CLAIM_EMAIL);
        return conversor;
    }

    /**
     * Força 10 (default). Não subir enquanto não houver rate limit no login (checklist item 11
     * do CLAUDE.md): hash caro em endpoint público e sem limite é vetor de negação de serviço.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties propriedades) {
        CorsConfiguration configuracao = new CorsConfiguration();
        configuracao.setAllowedOrigins(propriedades.origens());
        configuracao.setAllowedMethods(
                List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuracao.setAllowedHeaders(List.of("*"));
        configuracao.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource fonte = new UrlBasedCorsConfigurationSource();
        fonte.registerCorsConfiguration("/**", configuracao);
        return fonte;
    }

    /**
     * Falta de token e token inválido são tratados no filtro, que normalmente não passa pelo
     * {@code @RestControllerAdvice}. Delegando ao {@code handlerExceptionResolver}, esses erros
     * caem no mesmo handler dos erros de controller — então o frontend recebe um único formato
     * de erro para 401 e 403, em vez de dois.
     */
    @Bean
    AuthenticationEntryPoint pontoDeEntradaProblemDetail(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolvedor) {
        return (requisicao, resposta, excecao) ->
                resolvedor.resolveException(requisicao, resposta, null, excecao);
    }

    @Bean
    AccessDeniedHandler acessoNegadoProblemDetail(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolvedor) {
        return (requisicao, resposta, excecao) ->
                resolvedor.resolveException(requisicao, resposta, null, excecao);
    }
}
