package com.conectsol.solarsync.auth.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origens autorizadas a chamar a API. O solarsync-web roda em outra origem, então sem CORS
 * configurado o frontend simplesmente não consegue consumir a API.
 */
@ConfigurationProperties("solarsync.cors")
public record CorsProperties(List<String> origens) {

    public CorsProperties {
        origens = origens == null || origens.isEmpty()
                ? List.of("http://localhost:5173")
                : origens;
    }
}
