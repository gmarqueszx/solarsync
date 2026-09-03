package com.conectsol.solarsync.auth.dto;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.jwt.TokenService.ParDeTokens;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tipo,
        long expiraEmSegundos,
        UsuarioLogadoResponse usuario) {

    public static TokenResponse de(ParDeTokens tokens, Usuario usuario) {
        return new TokenResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                "Bearer",
                tokens.expiraEmSegundos(),
                UsuarioLogadoResponse.de(usuario));
    }
}
