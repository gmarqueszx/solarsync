package com.conectsol.solarsync.auth.jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.Papel;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.common.exception.UsuarioNaoAutorizadoException;

/**
 * Único emissor de token do sistema: o login por senha e a renovação por refresh token diferem
 * apenas em como provam a identidade, e ambos terminam em {@link #emitirPar(Usuario)}.
 */
@Service
public class TokenService {

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_NOME = "nome";
    public static final String CLAIM_PAPEIS = "papeis";
    public static final String CLAIM_TIPO = "token_tipo";

    public static final String TIPO_ACCESS = "access";
    public static final String TIPO_REFRESH = "refresh";

    private final JwtEncoder encoder;
    private final JwtDecoder decoderDeRefresh;
    private final JwtProperties propriedades;

    public TokenService(JwtEncoder encoder, JwtDecoder decoderDeRefresh, JwtProperties propriedades) {
        this.encoder = encoder;
        this.decoderDeRefresh = decoderDeRefresh;
        this.propriedades = propriedades;
    }

    public record ParDeTokens(String accessToken, String refreshToken, long expiraEmSegundos) {
    }

    public ParDeTokens emitirPar(Usuario usuario) {
        Instant agora = Instant.now();
        List<String> papeis = usuario.getPapeis().stream()
                .map(Papel::getNome)
                .map(NomePapel::name)
                .toList();

        String access = codificar(JwtClaimsSet.builder()
                .issuer(propriedades.emissor())
                .subject(String.valueOf(usuario.getId()))
                .issuedAt(agora)
                .expiresAt(agora.plus(propriedades.accessTokenDuracao()))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_EMAIL, usuario.getEmail())
                .claim(CLAIM_NOME, usuario.getNome())
                .claim(CLAIM_PAPEIS, papeis)
                .claim(CLAIM_TIPO, TIPO_ACCESS)
                .build());

        String refresh = codificar(JwtClaimsSet.builder()
                .issuer(propriedades.emissor())
                .subject(String.valueOf(usuario.getId()))
                .issuedAt(agora)
                .expiresAt(agora.plus(propriedades.refreshTokenDuracao()))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TIPO, TIPO_REFRESH)
                .build());

        return new ParDeTokens(access, refresh, propriedades.accessTokenDuracao().toSeconds());
    }

    /**
     * Valida o refresh token e devolve o id do usuário. Quem chama <b>precisa</b> reler o
     * usuário no banco e recusar se estiver inativo — é assim que desligar alguém corta o
     * acesso sem precisar de tabela de sessão.
     */
    public Long idDoUsuarioNoRefresh(String refreshToken) {
        try {
            return Long.valueOf(decoderDeRefresh.decode(refreshToken).getSubject());
        } catch (JwtException | NumberFormatException excecao) {
            throw new UsuarioNaoAutorizadoException(excecao);
        }
    }

    private String codificar(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
