package com.conectsol.solarsync.auth.jwt;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param emissor              claim {@code iss}, validado na entrada
 * @param segredo              segredo HS256, mínimo 32 bytes. Vem de {@code SOLARSYNC_JWT_SEGREDO}
 *                             no ambiente — nunca do repositório. Se vazio, um segredo aleatório
 *                             é gerado no boot (ver {@link JwtConfig}).
 * @param accessTokenDuracao   curto de propósito: é a janela máxima em que um usuário desativado
 *                             ainda consegue usar o sistema
 * @param refreshTokenDuracao  cobre um dia de trabalho, para ninguém relogar no meio do turno
 * @param segredoObrigatorio   {@code true} no perfil prod: sem segredo configurado, o boot falha
 *                             em vez de sortear um. {@code Boolean} e não {@code boolean} pela
 *                             mesma razão dos request DTOs — ausente vira falso, sem erro
 */
@ConfigurationProperties("solarsync.jwt")
public record JwtProperties(
        String emissor,
        String segredo,
        Duration accessTokenDuracao,
        Duration refreshTokenDuracao,
        Boolean segredoObrigatorio) {

    public JwtProperties {
        emissor = emissor == null ? "solarsync" : emissor;
        accessTokenDuracao = accessTokenDuracao == null ? Duration.ofMinutes(15) : accessTokenDuracao;
        refreshTokenDuracao = refreshTokenDuracao == null ? Duration.ofHours(8) : refreshTokenDuracao;
        segredoObrigatorio = Boolean.TRUE.equals(segredoObrigatorio);
    }
}
