package com.conectsol.solarsync.auth.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
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
import com.conectsol.solarsync.auth.login.LoginRateLimitProperties;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({ CorsProperties.class, LoginRateLimitProperties.class })
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
                        // O healthcheck do container roda sem token. Devolve só UP/DOWN
                        // (show-details=never) e o proxy reverso o bloqueia de fora — quem o
                        // alcança é o próprio Docker, em localhost dentro do container.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
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
     * Força 10 (default). Subir o custo só faz sentido agora que existe limite de tentativas
     * (`ControleDeTentativasDeLogin`, item 11 do checklist): hash caro em endpoint público e sem
     * limite é vetor de negação de serviço, e aumentar o fator sem o limite pioraria o ataque.
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

        // ⚠️ `allowedHeaders("*")` vale para o que o navegador ENVIA. Para o que ele deixa o
        // JavaScript LER da resposta, a lista é outra e é curtíssima por padrão — Retry-After não
        // está nela, então `resposta.headers.get('Retry-After')` devolvia null no navegador
        // mesmo com o servidor mandando o cabeçalho. O frontend caía de volta na mensagem
        // genérica e a contagem regressiva do login bloqueado nunca aparecia.
        //
        // Descoberto testando na interface de verdade em 19/09/2026; nenhum teste de backend
        // pegaria, porque CORS é regra do navegador e o MockMvc não a aplica.
        configuracao.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));

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
