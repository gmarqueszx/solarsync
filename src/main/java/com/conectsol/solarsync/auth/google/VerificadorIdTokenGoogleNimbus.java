package com.conectsol.solarsync.auth.google;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.conectsol.solarsync.common.exception.UsuarioNaoAutorizadoException;

/**
 * Valida o ID token do Google com um {@code JwtDecoder} apontado ao JWKS público do Google.
 * <p>
 * Este desenho (o frontend faz o Sign-In e manda o ID token) foi escolhido em vez do
 * Authorization Code com redirect porque mantém a API stateless e dispensa a dependência
 * {@code oauth2-client}: o decoder de JWKS já vem do starter de resource server.
 */
@Component
@EnableConfigurationProperties(GoogleProperties.class)
public class VerificadorIdTokenGoogleNimbus implements VerificadorIdTokenGoogle {

    private static final Logger log = LoggerFactory.getLogger(VerificadorIdTokenGoogleNimbus.class);

    private final GoogleProperties propriedades;
    /** Construído sob demanda: o JWKS só é buscado no primeiro login Google, não no boot. */
    private volatile NimbusJwtDecoder decoder;

    public VerificadorIdTokenGoogleNimbus(GoogleProperties propriedades) {
        this.propriedades = propriedades;
        if (!propriedades.habilitado()) {
            log.info("solarsync.google.client-id ausente: login com Google desabilitado.");
        }
    }

    @Override
    public IdentidadeGoogle verificar(String idToken) {
        if (!propriedades.habilitado()) {
            log.warn("Tentativa de login Google com a integração desabilitada");
            throw new UsuarioNaoAutorizadoException();
        }

        Jwt token;
        try {
            token = decoder().decode(idToken);
        } catch (JwtException excecao) {
            log.warn("ID token do Google recusado: {}", excecao.getMessage());
            throw new UsuarioNaoAutorizadoException(excecao);
        }

        return new IdentidadeGoogle(
                token.getClaimAsString("email"),
                Boolean.TRUE.equals(token.getClaim("email_verified")),
                token.getClaimAsString("hd"),
                token.getClaimAsString("name"));
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder atual = decoder;
        if (atual == null) {
            synchronized (this) {
                atual = decoder;
                if (atual == null) {
                    atual = construirDecoder();
                    decoder = atual;
                }
            }
        }
        return atual;
    }

    private NimbusJwtDecoder construirDecoder() {
        NimbusJwtDecoder novo = NimbusJwtDecoder
                .withJwkSetUri(propriedades.jwkSetUri())
                .build();

        // O Google emite iss em duas formas ('accounts.google.com' com e sem esquema), então
        // JwtIssuerValidator (que aceita um único valor) não serve.
        OAuth2TokenValidator<Jwt> emissor = new JwtClaimValidator<String>(
                "iss", valor -> valor != null && propriedades.issuers().contains(valor));

        OAuth2TokenValidator<Jwt> audiencia = new JwtClaimValidator<List<String>>(
                "aud", valor -> valor != null && valor.contains(propriedades.clientId()));

        novo.setJwtValidator(JwtValidators.createDefaultWithValidators(
                List.of(emissor, audiencia)));
        return novo;
    }
}
