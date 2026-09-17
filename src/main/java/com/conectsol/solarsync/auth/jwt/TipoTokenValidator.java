package com.conectsol.solarsync.auth.jwt;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Exige que o claim {@code token_tipo} seja o esperado. Sem isso um refresh token — que vive
 * 8h — funcionaria como bearer de acesso, anulando a janela curta do access token.
 */
public class TipoTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final String tipoEsperado;

    public TipoTokenValidator(String tipoEsperado) {
        this.tipoEsperado = tipoEsperado;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (tipoEsperado.equals(token.getClaimAsString(TokenService.CLAIM_TIPO))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "Token do tipo errado; esperado '%s'".formatted(tipoEsperado),
                null));
    }
}
