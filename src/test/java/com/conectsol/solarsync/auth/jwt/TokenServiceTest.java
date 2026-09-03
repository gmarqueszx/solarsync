package com.conectsol.solarsync.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.Papel;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.common.exception.UsuarioNaoAutorizadoException;

/** Usa os beans reais de {@link JwtConfig} com uma chave de teste — nada mockado. */
class TokenServiceTest {

    private static final String SEGREDO = "segredo-de-teste-com-mais-de-32-bytes!!";

    private TokenService tokenService;
    private JwtDecoder decoderDeAccess;
    private JwtDecoder decoderDeRefresh;

    @BeforeEach
    void montar() {
        JwtProperties propriedades = new JwtProperties(
                "solarsync", SEGREDO, Duration.ofMinutes(15), Duration.ofHours(8));
        JwtConfig config = new JwtConfig();
        SecretKey chave = config.chaveJwt(propriedades);

        decoderDeAccess = config.jwtDecoder(chave, propriedades);
        decoderDeRefresh = config.decoderDeRefresh(chave, propriedades);
        tokenService = new TokenService(
                config.jwtEncoder(chave), decoderDeRefresh, propriedades);
    }

    private static Usuario usuario() {
        // O id vem de BaseEntity, que o @Builder do Lombok não cobre (não é @SuperBuilder).
        Usuario usuario = Usuario.builder()
                .nome("Camila Analista")
                .email("camila@conectsol.com")
                .ativo(true)
                .papeis(Set.of(Papel.builder().nome(NomePapel.ANALISTA).build()))
                .build();
        usuario.setId(42L);
        return usuario;
    }

    @Test
    void accessTokenCarregaIdentidadeEPapeis() {
        var tokens = tokenService.emitirPar(usuario());

        Jwt decodificado = decoderDeAccess.decode(tokens.accessToken());

        assertThat(decodificado.getSubject()).isEqualTo("42");
        assertThat(decodificado.getClaimAsString(TokenService.CLAIM_EMAIL))
                .isEqualTo("camila@conectsol.com");
        assertThat(decodificado.getClaimAsString(TokenService.CLAIM_NOME))
                .isEqualTo("Camila Analista");
        assertThat(decodificado.getClaimAsStringList(TokenService.CLAIM_PAPEIS))
                .containsExactly("ANALISTA");
        assertThat(decodificado.getClaimAsString(TokenService.CLAIM_TIPO))
                .isEqualTo(TokenService.TIPO_ACCESS);
    }

    @Test
    void accessTokenExpiraNaJanelaConfigurada() {
        var tokens = tokenService.emitirPar(usuario());

        Jwt decodificado = decoderDeAccess.decode(tokens.accessToken());
        Duration validade = Duration.between(Instant.now(), decodificado.getExpiresAt());

        assertThat(validade).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));
        assertThat(tokens.expiraEmSegundos()).isEqualTo(900);
    }

    /** Sem esta separação, um refresh de 8h funcionaria como bearer e anularia a janela curta. */
    @Test
    void refreshTokenNaoEAceitoComoAccessToken() {
        var tokens = tokenService.emitirPar(usuario());

        assertThatThrownBy(() -> decoderDeAccess.decode(tokens.refreshToken()))
                .hasMessageContaining("tipo errado");
    }

    @Test
    void accessTokenNaoEAceitoComoRefreshToken() {
        var tokens = tokenService.emitirPar(usuario());

        assertThatThrownBy(() -> tokenService.idDoUsuarioNoRefresh(tokens.accessToken()))
                .isInstanceOf(UsuarioNaoAutorizadoException.class);
    }

    @Test
    void refreshTokenValidoDevolveOIdDoUsuario() {
        var tokens = tokenService.emitirPar(usuario());

        assertThat(tokenService.idDoUsuarioNoRefresh(tokens.refreshToken())).isEqualTo(42L);
    }

    @Test
    void tokenAssinadoComOutraChaveERecusado() {
        var tokens = tokenService.emitirPar(usuario());

        JwtProperties outras = new JwtProperties(
                "solarsync", "outro-segredo-completamente-diferente!!", null, null);
        JwtConfig config = new JwtConfig();
        SecretKey outraChave = new SecretKeySpec(
                outras.segredo().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256");

        assertThatThrownBy(() -> config.jwtDecoder(outraChave, outras)
                .decode(tokens.accessToken())).isNotNull();
    }

    @Test
    void segredoCurtoDemaisFalhaNoBootEmVezDeEnfraquecerAAssinatura() {
        JwtProperties fraco = new JwtProperties("solarsync", "curto", null, null);

        assertThatThrownBy(() -> new JwtConfig().chaveJwt(fraco))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void semSegredoConfiguradoGeraChaveAleatoriaParaNaoTravarODev() {
        JwtProperties semSegredo = new JwtProperties("solarsync", null, null, null);

        assertThat(new JwtConfig().chaveJwt(semSegredo)).isNotNull();
    }
}
