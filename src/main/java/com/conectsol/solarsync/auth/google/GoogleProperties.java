package com.conectsol.solarsync.auth.google;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param clientId            OAuth client ID do Google Cloud, validado como {@code aud} do ID
 *                            token. Vazio desabilita o login Google (dev sem configuração).
 * @param dominiosPermitidos  segunda barreira contra auto-cadastro: só e-mail destes domínios
 *                            é aceito, mesmo que o token do Google seja legítimo
 */
@ConfigurationProperties("solarsync.google")
public record GoogleProperties(
        String clientId,
        List<String> dominiosPermitidos,
        String jwkSetUri,
        List<String> issuers) {

    public GoogleProperties {
        dominiosPermitidos = dominiosPermitidos == null || dominiosPermitidos.isEmpty()
                ? List.of("conectsol.com")
                : dominiosPermitidos;
        jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank()
                ? "https://www.googleapis.com/oauth2/v3/certs"
                : jwkSetUri;
        issuers = issuers == null || issuers.isEmpty()
                ? List.of("https://accounts.google.com", "accounts.google.com")
                : issuers;
    }

    public boolean habilitado() {
        return clientId != null && !clientId.isBlank();
    }
}
