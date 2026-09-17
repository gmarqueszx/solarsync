package com.conectsol.solarsync.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);
    private static final int BYTES_MINIMOS_HS256 = 32;

    /**
     * Segredo HS256. Escolhemos simétrico porque não há terceiro validando nosso token — é uma
     * API só —, então chave assimétrica só adicionaria a operação de gerar, guardar e rotar PEM
     * no servidor.
     * <p>
     * Sem {@code solarsync.jwt.segredo} configurado, gera um segredo aleatório: assim nenhum
     * segredo precisa entrar no repositório e o build/dev roda sem configuração. O preço é que
     * os tokens não sobrevivem a um restart — inaceitável em produção, onde a variável de
     * ambiente é obrigatória.
     */
    @Bean
    SecretKey chaveJwt(JwtProperties propriedades) {
        String segredo = propriedades.segredo();
        if (segredo == null || segredo.isBlank()) {
            log.warn("solarsync.jwt.segredo ausente: gerando segredo aleatório. "
                    + "Os tokens emitidos NÃO sobrevivem a um restart. "
                    + "Em produção defina SOLARSYNC_JWT_SEGREDO.");
            byte[] aleatorio = new byte[BYTES_MINIMOS_HS256];
            new SecureRandom().nextBytes(aleatorio);
            return new SecretKeySpec(aleatorio, "HmacSHA256");
        }

        byte[] bytes = segredo.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < BYTES_MINIMOS_HS256) {
            throw new IllegalStateException(
                    "solarsync.jwt.segredo precisa de ao menos %d bytes para HS256; recebeu %d"
                            .formatted(BYTES_MINIMOS_HS256, bytes.length));
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey chaveJwt) {
        return NimbusJwtEncoder.withSecretKey(chaveJwt).algorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Decoder dos access tokens — é o que o resource server usa em toda requisição, por isso
     * {@code @Primary}. Recusa refresh token apresentado como bearer.
     */
    @Bean
    @Primary
    JwtDecoder jwtDecoder(SecretKey chaveJwt, JwtProperties propriedades) {
        return decoderDoTipo(chaveJwt, propriedades, TokenService.TIPO_ACCESS);
    }

    /** Usado apenas por {@code POST /api/auth/refresh}. */
    @Bean
    JwtDecoder decoderDeRefresh(SecretKey chaveJwt, JwtProperties propriedades) {
        return decoderDoTipo(chaveJwt, propriedades, TokenService.TIPO_REFRESH);
    }

    private JwtDecoder decoderDoTipo(SecretKey chave, JwtProperties propriedades, String tipo) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(chave)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validadores = JwtValidators.createDefaultWithValidators(
                List.of(JwtValidators.createDefaultWithIssuer(propriedades.emissor()),
                        new TipoTokenValidator(tipo)));
        decoder.setJwtValidator(validadores);
        return decoder;
    }
}
